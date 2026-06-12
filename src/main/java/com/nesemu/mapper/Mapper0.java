package com.nesemu.mapper;

import com.nesemu.cartridge.Mirroring;

/**
 * Mapper 0 - NROM.
 *
 * <p>The simplest cartridge layout, with no bank switching:</p>
 * <ul>
 *   <li>PRG ROM: either 16 KB (mirrored into both $8000-$BFFF and $C000-$FFFF)
 *       or 32 KB (mapped directly across $8000-$FFFF).</li>
 *   <li>CHR: 8 KB of CHR ROM (or CHR RAM if the header reported 0 CHR banks),
 *       mapped to PPU $0000-$1FFF.</li>
 *   <li>Optional 8 KB PRG RAM at $6000-$7FFF.</li>
 *   <li>Fixed nametable mirroring taken from the iNES header.</li>
 * </ul>
 *
 * <p>Super Mario Bros. is a 32 KB PRG / 8 KB CHR NROM cartridge with vertical
 * mirroring, so it is fully covered here.</p>
 */
public final class Mapper0 extends Mapper {

    private final int prgMask; // 0x3FFF for 16 KB, 0x7FFF for 32 KB

    public Mapper0(byte[] prgRom, byte[] chrMem, int prgBanks, int chrBanks,
                   boolean chrIsRam, Mirroring mirroring) {
        super(prgRom, chrMem, prgBanks, chrBanks, chrIsRam, mirroring);
        // A single 16 KB bank wraps every 16 KB; 32 KB spans the full window.
        this.prgMask = (prgBanks > 1) ? 0x7FFF : 0x3FFF;
    }

    @Override
    public int cpuRead(int addr) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            return prgRam[addr & 0x1FFF] & 0xFF;
        }
        if (addr >= 0x8000 && addr <= 0xFFFF) {
            return prgRom[(addr - 0x8000) & prgMask] & 0xFF;
        }
        return -1; // not decoded by this mapper
    }

    @Override
    public void cpuWrite(int addr, int value) {
        if (addr >= 0x6000 && addr <= 0x7FFF) {
            prgRam[addr & 0x1FFF] = (byte) value;
        }
        // Writes to $8000-$FFFF have no effect on NROM (it is ROM).
    }

    @Override
    public int ppuRead(int addr) {
        addr &= 0x1FFF;
        return chrMem[addr] & 0xFF;
    }

    @Override
    public void ppuWrite(int addr, int value) {
        addr &= 0x1FFF;
        if (chrIsRam) {
            chrMem[addr] = (byte) value;
        }
        // Writes to CHR ROM are ignored.
    }
}
