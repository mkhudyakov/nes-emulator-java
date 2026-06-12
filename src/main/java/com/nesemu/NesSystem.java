package com.nesemu;

import com.nesemu.apu.Apu;
import com.nesemu.cartridge.Cartridge;
import com.nesemu.controller.Controller;
import com.nesemu.cpu.Bus;
import com.nesemu.cpu.Cpu6502;
import com.nesemu.ppu.Ppu;

/**
 * The NES console: the memory bus that ties every component together.
 *
 * <p>This class implements {@link Bus}, the CPU's view of the address space, and
 * owns the master clock that keeps the CPU and PPU in step. On real hardware the
 * PPU runs three times as fast as the CPU, so the master clock ticks the PPU on
 * every step and the CPU on every third step.
 *
 * <h2>CPU memory map</h2>
 * <pre>
 *   $0000-$1FFF  2KB internal RAM, mirrored every $0800
 *   $2000-$3FFF  PPU registers, mirrored every 8 bytes
 *   $4014        OAM DMA
 *   $4016        Controller 1 (read) / strobe (write, both pads)
 *   $4017        Controller 2 (read)
 *   $4020-$FFFF  Cartridge (PRG ROM, PRG RAM) via the mapper
 * </pre>
 */
public final class NesSystem implements Bus {

    private final Cpu6502 cpu;
    private final Ppu ppu;
    private final Apu apu = new Apu();
    private final Controller controller1 = new Controller();
    private final Controller controller2 = new Controller();

    private final int[] ram = new int[2048];
    private Cartridge cartridge;

    /** Counts master-clock ticks; CPU runs on counts divisible by 3. */
    private long systemClockCounter = 0;

    // --- OAM DMA state ($4014) ---------------------------------------------
    private boolean dmaTransfer = false;
    private boolean dmaDummy = true;   // wait for an even cycle to align
    private int dmaPage = 0x00;        // high byte of the source address
    private int dmaAddr = 0x00;        // low byte, 0x00..0xFF
    private int dmaData = 0x00;        // byte latched on a read cycle

    public NesSystem() {
        this.ppu = new Ppu();
        this.cpu = new Cpu6502(this);
        // The DMC channel fetches its samples from CPU memory.
        this.apu.setDmcReader(this::cpuRead);
    }

    // ------------------------------------------------------------------
    // Cartridge management
    // ------------------------------------------------------------------

    public void insertCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
        ppu.connectCartridge(cartridge);
    }

    public Cartridge getCartridge() {
        return cartridge;
    }

    // ------------------------------------------------------------------
    // Bus interface — the CPU's address space
    // ------------------------------------------------------------------

    @Override
    public int cpuRead(int addr) {
        addr &= 0xFFFF;
        if (addr <= 0x1FFF) {
            return ram[addr & 0x07FF] & 0xFF;
        } else if (addr <= 0x3FFF) {
            return ppu.cpuRead(addr & 0x2007) & 0xFF;
        } else if (addr == 0x4015) {
            return apu.readStatus() & 0xFF;
        } else if (addr == 0x4016) {
            return controller1.read() & 0xFF;
        } else if (addr == 0x4017) {
            return controller2.read() & 0xFF;
        } else if (addr >= 0x4020) {
            int data = cartridge != null ? cartridge.cpuRead(addr) : -1;
            return data < 0 ? 0 : data & 0xFF;
        }
        // Remaining $4000-$4014 registers are write-only.
        return 0;
    }

    @Override
    public void cpuWrite(int addr, int value) {
        addr &= 0xFFFF;
        value &= 0xFF;
        if (addr <= 0x1FFF) {
            ram[addr & 0x07FF] = value;
        } else if (addr <= 0x3FFF) {
            ppu.cpuWrite(addr & 0x2007, value);
        } else if (addr >= 0x4000 && addr <= 0x4013) {
            apu.writeRegister(addr, value);
        } else if (addr == 0x4014) {
            // Writing the high byte of a page here starts a 256-byte OAM DMA.
            dmaPage = value;
            dmaAddr = 0x00;
            dmaTransfer = true;
            dmaDummy = true;
        } else if (addr == 0x4015) {
            apu.writeRegister(addr, value);
        } else if (addr == 0x4016) {
            // The strobe line is shared by both controllers.
            controller1.write(value);
            controller2.write(value);
        } else if (addr == 0x4017) {
            // $4017 writes go to the APU frame counter (reads are controller 2).
            apu.writeRegister(addr, value);
        } else if (addr >= 0x4020) {
            if (cartridge != null) {
                cartridge.cpuWrite(addr, value);
            }
        }
    }

    // ------------------------------------------------------------------
    // Master clock
    // ------------------------------------------------------------------

    /** Advance the whole machine by one PPU dot (one master-clock tick). */
    public void clock() {
        ppu.clock();

        // The CPU runs at one third of the PPU's rate.
        if (systemClockCounter % 3 == 0) {
            // The APU advances one cycle for every CPU cycle, even while the CPU
            // is suspended for OAM DMA.
            apu.clock();
            if (dmaTransfer) {
                stepDma();
            } else {
                cpu.clock();
            }
            // A frame-counter or DMC interrupt is level-triggered.
            if (apu.isIrqAsserted()) {
                cpu.irq();
            }
        }

        // The PPU may raise an NMI at the start of VBlank.
        if (ppu.isNmiRequested()) {
            ppu.clearNmiRequest();
            cpu.nmi();
        }

        systemClockCounter++;
    }

    /**
     * One DMA sub-step. DMA suspends the CPU and copies 256 bytes from CPU page
     * {@code $dmaPage00} into PPU OAM. It alternates read cycles (even) and write
     * cycles (odd) after first stalling to land on an even cycle.
     */
    private void stepDma() {
        if (dmaDummy) {
            if (systemClockCounter % 2 == 1) {
                dmaDummy = false;
            }
            return;
        }
        if (systemClockCounter % 2 == 0) {
            dmaData = cpuRead((dmaPage << 8) | dmaAddr);
        } else {
            ppu.oamDmaWrite(dmaData);
            dmaAddr = (dmaAddr + 1) & 0xFF;
            if (dmaAddr == 0x00) {
                // Wrapped past $FF: all 256 bytes transferred.
                dmaTransfer = false;
                dmaDummy = true;
            }
        }
    }

    /** Run the machine until the PPU signals a completed frame. */
    public void stepFrame() {
        do {
            clock();
        } while (!ppu.isFrameComplete());
        ppu.clearFrameComplete();
    }

    /** Execute a single CPU instruction's worth of master clocks (debugging). */
    public void stepInstruction() {
        // Finish any in-progress instruction.
        do {
            clock();
        } while (!cpu.isInstructionComplete());
        // Run the next full instruction.
        do {
            clock();
        } while (cpu.isInstructionComplete());
        do {
            clock();
        } while (!cpu.isInstructionComplete());
    }

    // ------------------------------------------------------------------
    // Reset / power
    // ------------------------------------------------------------------

    public void reset() {
        if (cartridge != null) {
            cartridge.reset();
        }
        cpu.reset();
        ppu.reset();
        apu.reset();
        controller1.reset();
        controller2.reset();
        systemClockCounter = 0;
        dmaTransfer = false;
        dmaDummy = true;
        dmaPage = 0;
        dmaAddr = 0;
        dmaData = 0;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Cpu6502 getCpu() {
        return cpu;
    }

    public Ppu getPpu() {
        return ppu;
    }

    public Apu getApu() {
        return apu;
    }

    public Controller getController1() {
        return controller1;
    }

    public Controller getController2() {
        return controller2;
    }

    public int[] getFramebuffer() {
        return ppu.getFramebuffer();
    }
}
