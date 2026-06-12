package com.nesemu.mapper;

import com.nesemu.cartridge.Mirroring;

/**
 * A cartridge mapper. Mappers are the circuitry on the cartridge that decides
 * how the CPU's $4020-$FFFF address space and the PPU's $0000-$1FFF address
 * space map onto the physical PRG and CHR memory chips, and they may also
 * provide bank switching and extra registers.
 *
 * <p>This emulator currently implements Mapper 0 (NROM) only, which is enough
 * for Super Mario Bros., Donkey Kong, and many other early titles.</p>
 */
public abstract class Mapper {

    protected final byte[] prgRom;
    protected final byte[] chrMem;   // CHR ROM, or CHR RAM if the cartridge had none
    protected final byte[] prgRam;   // 8 KB of optional work/save RAM at $6000-$7FFF
    protected final int prgBanks;    // number of 16 KB PRG banks
    protected final int chrBanks;    // number of 8 KB CHR banks (0 => CHR RAM)
    protected final boolean chrIsRam;
    private Mirroring mirroring;

    protected Mapper(byte[] prgRom, byte[] chrMem, int prgBanks, int chrBanks,
                     boolean chrIsRam, Mirroring mirroring) {
        this.prgRom = prgRom;
        this.chrMem = chrMem;
        this.prgRam = new byte[0x2000];
        this.prgBanks = prgBanks;
        this.chrBanks = chrBanks;
        this.chrIsRam = chrIsRam;
        this.mirroring = mirroring;
    }

    /**
     * Read a byte the CPU requested in the cartridge address space.
     *
     * @return the byte value (0-255), or -1 if this mapper does not decode the address
     *         (the bus should then treat it as open bus / leave the value unchanged).
     */
    public abstract int cpuRead(int addr);

    /** Handle a CPU write into the cartridge address space (bank switching, PRG-RAM, etc). */
    public abstract void cpuWrite(int addr, int value);

    /** Read a byte the PPU requested from CHR memory ($0000-$1FFF). */
    public abstract int ppuRead(int addr);

    /** Handle a PPU write into CHR memory (only meaningful for CHR RAM boards). */
    public abstract void ppuWrite(int addr, int value);

    /** Current nametable mirroring. Most simple mappers keep the header value forever. */
    public Mirroring getMirroring() {
        return mirroring;
    }

    protected void setMirroring(Mirroring mirroring) {
        this.mirroring = mirroring;
    }

    /** Called on console reset. Default is a no-op; banked mappers override this. */
    public void reset() {
        // no-op by default
    }
}
