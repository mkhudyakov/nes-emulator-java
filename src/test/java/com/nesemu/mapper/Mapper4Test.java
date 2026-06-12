package com.nesemu.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nesemu.cartridge.Mirroring;
import org.junit.jupiter.api.Test;

class Mapper4Test {

    private static final int PRG_8K = 0x2000;
    private static final int CHR_1K = 0x0400;

    /** Build an MMC3 with {@code prg8kBanks} 8 KB PRG banks and {@code chr1kBanks} 1 KB CHR banks,
     *  stamping the first byte of every bank with its index so banking is observable. */
    private static Mapper4 make(int prg8kBanks, int chr1kBanks) {
        byte[] prg = new byte[prg8kBanks * PRG_8K];
        for (int b = 0; b < prg8kBanks; b++) {
            prg[b * PRG_8K] = (byte) b;
        }
        byte[] chr = new byte[chr1kBanks * CHR_1K];
        for (int b = 0; b < chr1kBanks; b++) {
            chr[b * CHR_1K] = (byte) b;
        }
        // prgBanks/chrBanks (16K/8K units) are unused by Mapper4's own math.
        return new Mapper4(prg, chr, prg8kBanks / 2, chr1kBanks / 8, false,
                Mirroring.VERTICAL, false);
    }

    /** Select bank register {@code reg} and write {@code value} into it. */
    private static void setBank(Mapper4 m, int reg, int value) {
        m.cpuWrite(0x8000, reg);       // even: bank select
        m.cpuWrite(0x8001, value);     // odd: bank data
    }

    @Test
    void fixedBanksMapToLastTwoPrgBanks() {
        Mapper4 m = make(8, 8); // banks 0..7
        // In PRG mode 0 the last two 8 KB windows are fixed to the last two banks.
        assertEquals(6, m.cpuRead(0xC000)); // second-to-last
        assertEquals(7, m.cpuRead(0xE000)); // last
    }

    @Test
    void prgMode0SwitchesLowWindows() {
        Mapper4 m = make(8, 8);
        m.cpuWrite(0x8000, 0x06);   // mode 0, target R6
        m.cpuWrite(0x8001, 3);      // R6 = bank 3 -> $8000
        m.cpuWrite(0x8000, 0x07);   // target R7
        m.cpuWrite(0x8001, 5);      // R7 = bank 5 -> $A000
        assertEquals(3, m.cpuRead(0x8000));
        assertEquals(5, m.cpuRead(0xA000));
        assertEquals(6, m.cpuRead(0xC000));
        assertEquals(7, m.cpuRead(0xE000));
    }

    @Test
    void prgMode1SwapsFixedAndSwitchable() {
        Mapper4 m = make(8, 8);
        m.cpuWrite(0x8000, 0x46);   // mode 1 (bit 6) + target R6
        m.cpuWrite(0x8001, 3);      // R6 -> $C000 in mode 1
        assertEquals(6, m.cpuRead(0x8000)); // second-to-last is now fixed at $8000
        assertEquals(3, m.cpuRead(0xC000)); // R6 moved to $C000
        assertEquals(7, m.cpuRead(0xE000)); // last still fixed
    }

    @Test
    void chrTwoKbAndOneKbBanks() {
        Mapper4 m = make(8, 8);
        setBank(m, 0, 2); // R0: 2 KB bank -> CHR $0000-$07FF = banks 2,3
        setBank(m, 2, 5); // R2: 1 KB bank -> CHR $1000-$13FF = bank 5
        assertEquals(2, m.ppuRead(0x0000));
        assertEquals(3, m.ppuRead(0x0400));
        assertEquals(5, m.ppuRead(0x1000));
    }

    @Test
    void chrInversionSwapsHalves() {
        Mapper4 m = make(8, 8);
        setBank(m, 0, 2); // R0 (2 KB)
        setBank(m, 2, 5); // R2 (1 KB)
        m.cpuWrite(0x8000, 0x80); // set CHR inversion, target R0
        // With inversion, the 1 KB banks move to $0000 and the 2 KB banks to $1000.
        assertEquals(5, m.ppuRead(0x0000)); // R2 now at $0000
        assertEquals(2, m.ppuRead(0x1000)); // R0 now at $1000
    }

    @Test
    void mirroringRegisterTogglesVerticalHorizontal() {
        Mapper4 m = make(8, 8);
        m.cpuWrite(0xA000, 0x00);
        assertEquals(Mirroring.VERTICAL, m.getMirroring());
        m.cpuWrite(0xA000, 0x01);
        assertEquals(Mirroring.HORIZONTAL, m.getMirroring());
    }

    @Test
    void scanlineIrqFiresWhenCounterReachesZero() {
        Mapper4 m = make(8, 8);
        m.cpuWrite(0xC000, 2);  // latch = 2
        m.cpuWrite(0xC001, 0);  // request reload
        m.cpuWrite(0xE001, 0);  // enable IRQ

        m.clockScanlineCounter(); // reload -> counter = 2
        assertFalse(m.isIrqAsserted());
        m.clockScanlineCounter(); // 2 -> 1
        assertFalse(m.isIrqAsserted());
        m.clockScanlineCounter(); // 1 -> 0, assert
        assertTrue(m.isIrqAsserted());
    }

    @Test
    void disablingIrqAcknowledgesIt() {
        Mapper4 m = make(8, 8);
        m.cpuWrite(0xC000, 1);
        m.cpuWrite(0xC001, 0);
        m.cpuWrite(0xE001, 0);
        m.clockScanlineCounter(); // reload to 1
        m.clockScanlineCounter(); // 1 -> 0, assert
        assertTrue(m.isIrqAsserted());
        m.cpuWrite(0xE000, 0);    // disable + acknowledge
        assertFalse(m.isIrqAsserted());
    }
}
