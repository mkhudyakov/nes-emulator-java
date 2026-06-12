package com.nesemu.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nesemu.cartridge.Mirroring;
import org.junit.jupiter.api.Test;

class Mapper0Test {

    private static Mapper0 make(int prgBanks, boolean chrRam) {
        byte[] prg = new byte[prgBanks * 16384];
        // Mark the first and last byte of PRG so mirroring is observable.
        prg[0] = (byte) 0x11;
        prg[prg.length - 1] = (byte) 0x99;
        byte[] chr = new byte[8192];
        return new Mapper0(prg, chr, prgBanks, chrRam ? 0 : 1, chrRam, Mirroring.HORIZONTAL);
    }

    @Test
    void sixteenKbPrgIsMirrored() {
        Mapper0 m = make(1, true);
        // $8000 and $C000 should read the same byte when only one 16KB bank exists.
        assertEquals(0x11, m.cpuRead(0x8000));
        assertEquals(0x11, m.cpuRead(0xC000));
        // Last byte of the bank appears at $BFFF and $FFFF.
        assertEquals(0x99, m.cpuRead(0xBFFF));
        assertEquals(0x99, m.cpuRead(0xFFFF));
    }

    @Test
    void thirtyTwoKbPrgIsNotMirrored() {
        Mapper0 m = make(2, true);
        // $8000 reads the very first byte; $FFFF reads the very last.
        assertEquals(0x11, m.cpuRead(0x8000));
        assertEquals(0x99, m.cpuRead(0xFFFF));
    }

    @Test
    void prgRamReadWrite() {
        Mapper0 m = make(1, true);
        m.cpuWrite(0x6000, 0x5A);
        assertEquals(0x5A, m.cpuRead(0x6000));
    }

    @Test
    void chrRamIsWritable() {
        Mapper0 m = make(1, true);
        m.ppuWrite(0x0001, 0x7E);
        assertEquals(0x7E, m.ppuRead(0x0001));
    }

    @Test
    void belowCartridgeRangeIsNotDecoded() {
        Mapper0 m = make(1, true);
        // Addresses below $6000 are not the cartridge's; cpuRead returns -1.
        assertEquals(-1, m.cpuRead(0x4020));
    }
}
