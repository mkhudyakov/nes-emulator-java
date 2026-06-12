package com.nesemu.mapper;

import com.nesemu.cartridge.Mirroring;

/**
 * Mapper 4 - MMC3 (and the compatible MMC6).
 *
 * <p>One of the most common NES boards: it backs Super Mario Bros. 2 and 3,
 * Mega Man 3-6, Kirby's Adventure, and a large fraction of the late library.
 * It adds three things over NROM:</p>
 *
 * <ul>
 *   <li><b>PRG banking</b> - two switchable 8 KB windows at $8000 and $C000,
 *       with the remaining two windows fixed to the last two banks. Which of the
 *       two switchable windows is at $8000 vs. $C000 is selected by a mode bit.</li>
 *   <li><b>CHR banking</b> - two 2 KB and four 1 KB windows across the pattern
 *       tables, optionally swapped between $0000 and $1000 by the A12-inversion
 *       bit.</li>
 *   <li><b>A scanline IRQ counter</b> - clocked once per rendered scanline (this
 *       emulator drives it from the PPU rather than from PPU A12 directly). When
 *       the counter reaches zero it raises an IRQ, which games use to split the
 *       screen for status bars and parallax.</li>
 * </ul>
 *
 * <p>Registers live in $8000-$FFFF and are decoded by the high address bits and
 * the low (even/odd) address bit:</p>
 * <pre>
 *   $8000 even  bank select  (target reg, PRG mode, CHR inversion)
 *   $8001 odd   bank data    (value for the selected R0..R7)
 *   $A000 even  mirroring    (bit0: 0=vertical, 1=horizontal)
 *   $A001 odd   PRG-RAM protect
 *   $C000 even  IRQ latch    (reload value)
 *   $C001 odd   IRQ reload   (force a reload on the next clock)
 *   $E000 even  IRQ disable + acknowledge
 *   $E001 odd   IRQ enable
 * </pre>
 */
public final class Mapper4 extends Mapper {

    // Bank registers R0..R7: R0/R1 select 2 KB CHR banks, R2-R5 select 1 KB CHR
    // banks, and R6/R7 select 8 KB PRG banks.
    private final int[] bankRegister = new int[8];
    private int targetRegister;       // which R the next $8001 write updates
    private boolean prgBankMode;      // $8000 bit 6
    private boolean chrInversion;     // $8000 bit 7

    private final boolean fourScreen; // four-screen carts ignore the $A000 register
    private boolean prgRamEnabled = true;

    private final int prg8kCount;     // number of 8 KB PRG banks
    private final int chr1kCount;     // number of 1 KB CHR banks

    // Resolved window offsets into the PRG/CHR arrays, recomputed when a banking
    // register changes. Four 8 KB PRG windows, eight 1 KB CHR windows.
    private final int[] prgOffset = new int[4];
    private final int[] chrOffset = new int[8];

    // Scanline IRQ counter.
    private int irqLatch;
    private int irqCounter;
    private boolean irqReload;
    private boolean irqEnabled;
    private boolean irqAsserted;

    public Mapper4(byte[] prgRom, byte[] chrMem, int prgBanks, int chrBanks,
                   boolean chrIsRam, Mirroring mirroring, boolean fourScreen) {
        super(prgRom, chrMem, prgBanks, chrBanks, chrIsRam, mirroring);
        this.fourScreen = fourScreen;
        this.prg8kCount = Math.max(1, prgRom.length / 0x2000);
        this.chr1kCount = Math.max(1, chrMem.length / 0x400);
        reset();
    }

    @Override
    public void reset() {
        for (int i = 0; i < bankRegister.length; i++) {
            bankRegister[i] = 0;
        }
        targetRegister = 0;
        prgBankMode = false;
        chrInversion = false;
        prgRamEnabled = true;
        irqLatch = 0;
        irqCounter = 0;
        irqReload = false;
        irqEnabled = false;
        irqAsserted = false;
        updateBanks();
    }

    /** Recompute every PRG/CHR window offset from the current register state. */
    private void updateBanks() {
        int last = prg8kCount - 1;
        int secondLast = Math.max(0, prg8kCount - 2);
        int r6 = (bankRegister[6] % prg8kCount) * 0x2000;
        int r7 = (bankRegister[7] % prg8kCount) * 0x2000;
        if (!prgBankMode) {
            // $8000=R6, $A000=R7, $C000=second-last, $E000=last
            prgOffset[0] = r6;
            prgOffset[1] = r7;
            prgOffset[2] = secondLast * 0x2000;
            prgOffset[3] = last * 0x2000;
        } else {
            // $8000=second-last, $A000=R7, $C000=R6, $E000=last
            prgOffset[0] = secondLast * 0x2000;
            prgOffset[1] = r7;
            prgOffset[2] = r6;
            prgOffset[3] = last * 0x2000;
        }

        // R0/R1 are 2 KB banks (low bit of the register ignored), spanning two
        // consecutive 1 KB slots; R2..R5 are single 1 KB banks.
        int r0 = bankRegister[0] & 0xFE;
        int r1 = bankRegister[1] & 0xFE;
        int[] map = chrInversion
                ? new int[]{bankRegister[2], bankRegister[3], bankRegister[4], bankRegister[5], r0, r0 + 1, r1, r1 + 1}
                : new int[]{r0, r0 + 1, r1, r1 + 1, bankRegister[2], bankRegister[3], bankRegister[4], bankRegister[5]};
        for (int i = 0; i < 8; i++) {
            chrOffset[i] = (map[i] % chr1kCount) * 0x400;
        }
    }

    // ------------------------------------------------------------------
    // CPU side
    // ------------------------------------------------------------------

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            return prgRam[addr & 0x1FFF] & 0xFF;
        }
        if (addr >= 0x8000 && addr <= 0xFFFF) {
            int window = (addr - 0x8000) >> 13;   // 0..3, one per 8 KB window
            return prgRom[prgOffset[window] + (addr & 0x1FFF)] & 0xFF;
        }
        return -1; // not decoded
    }

    @Override
    public void cpuWrite(int addr, int value) {
        value &= 0xFF;
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            if (prgRamEnabled) {
                prgRam[addr & 0x1FFF] = (byte) value;
            }
            return;
        }
        if (addr < 0x8000) {
            return;
        }

        boolean odd = (addr & 0x0001) != 0;
        if (addr <= 0x9FFF) {
            if (!odd) {                  // $8000 bank select
                targetRegister = value & 0x07;
                prgBankMode = (value & 0x40) != 0;
                chrInversion = (value & 0x80) != 0;
                updateBanks();
            } else {                     // $8001 bank data
                bankRegister[targetRegister] = value;
                updateBanks();
            }
        } else if (addr <= 0xBFFF) {
            if (!odd) {                  // $A000 mirroring
                if (!fourScreen) {
                    setMirroring((value & 0x01) == 0 ? Mirroring.VERTICAL : Mirroring.HORIZONTAL);
                }
            } else {                     // $A001 PRG-RAM protect
                prgRamEnabled = (value & 0x80) != 0;
            }
        } else if (addr <= 0xDFFF) {
            if (!odd) {                  // $C000 IRQ latch
                irqLatch = value;
            } else {                     // $C001 IRQ reload
                irqReload = true;
                irqCounter = 0;
            }
        } else {
            if (!odd) {                  // $E000 IRQ disable + acknowledge
                irqEnabled = false;
                irqAsserted = false;
            } else {                     // $E001 IRQ enable
                irqEnabled = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // PPU side
    // ------------------------------------------------------------------

    @Override
    public int ppuRead(int addr) {
        addr &= 0x1FFF;
        int window = addr >> 10;          // 0..7, one per 1 KB window
        return chrMem[chrOffset[window] + (addr & 0x03FF)] & 0xFF;
    }

    @Override
    public void ppuWrite(int addr, int value) {
        addr &= 0x1FFF;
        if (chrIsRam) {
            int window = addr >> 10;
            chrMem[chrOffset[window] + (addr & 0x03FF)] = (byte) value;
        }
    }

    // ------------------------------------------------------------------
    // Scanline IRQ counter
    // ------------------------------------------------------------------

    @Override
    public void clockScanlineCounter() {
        // MMC3 (newer revision) behaviour: reload when zero or when a reload was
        // requested, otherwise decrement; assert the IRQ when it lands on zero.
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch;
            irqReload = false;
        } else {
            irqCounter--;
        }
        if (irqCounter == 0 && irqEnabled) {
            irqAsserted = true;
        }
    }

    @Override
    public boolean isIrqAsserted() {
        return irqAsserted;
    }

    @Override
    public void clearIrq() {
        irqAsserted = false;
    }
}
