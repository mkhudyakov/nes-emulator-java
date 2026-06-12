package com.nesemu.cartridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CartridgeTest {

    /** Build a minimal valid iNES image: header + the requested PRG/CHR banks. */
    private static byte[] buildImage(int prgBanks, int chrBanks, int flags6, int flags7) {
        int prgSize = prgBanks * 16384;
        int chrSize = chrBanks * 8192;
        byte[] data = new byte[16 + prgSize + chrSize];
        data[0] = 'N';
        data[1] = 'E';
        data[2] = 'S';
        data[3] = 0x1A;
        data[4] = (byte) prgBanks;
        data[5] = (byte) chrBanks;
        data[6] = (byte) flags6;
        data[7] = (byte) flags7;
        return data;
    }

    @Test
    void parsesHeaderFields() {
        // 2 PRG banks, 1 CHR bank, mapper 0, vertical mirroring (flags6 bit 0 set).
        byte[] image = buildImage(2, 1, 0x01, 0x00);
        INesHeader header = INesHeader.parse(image);

        assertEquals(2, header.getPrgRomBanks());
        assertEquals(1, header.getChrRomBanks());
        assertEquals(0, header.getMapperId());
        assertTrue(header.isVerticalMirroring());
        assertFalse(header.hasTrainer());
        assertFalse(header.hasBattery());
        assertEquals(Mirroring.VERTICAL, header.getMirroring());
    }

    @Test
    void combinesMapperNibbles() {
        // Low nibble in flags6 high bits, high nibble in flags7 high bits => mapper 0x12.
        byte[] image = buildImage(1, 1, 0x20, 0x10);
        INesHeader header = INesHeader.parse(image);
        assertEquals(0x12, header.getMapperId());
    }

    @Test
    void rejectsBadMagic() {
        byte[] image = buildImage(1, 1, 0, 0);
        image[0] = 'X';
        assertThrows(InvalidRomException.class, () -> INesHeader.parse(image));
    }

    @Test
    void rejectsTooShort() {
        byte[] tiny = new byte[8];
        assertThrows(InvalidRomException.class, () -> INesHeader.parse(tiny));
    }

    @Test
    void loadsCartridgeAndAllocatesChrRamWhenZeroChrBanks() {
        // 1 PRG bank, 0 CHR banks => CHR RAM should be allocated and writable.
        byte[] image = buildImage(1, 0, 0x00, 0x00);
        Cartridge cart = Cartridge.fromBytes(image);

        assertEquals(0, cart.getHeader().getMapperId());
        // CHR RAM is writable; CHR ROM would not be.
        cart.ppuWrite(0x0000, 0x42);
        assertEquals(0x42, cart.ppuRead(0x0000));
    }

    @Test
    void prgRomReadsThroughMapper() {
        byte[] image = buildImage(1, 1, 0x00, 0x00);
        // Put a recognisable byte at the start of PRG ROM (offset 16 in the image).
        image[16] = (byte) 0xAB;
        Cartridge cart = Cartridge.fromBytes(image);
        // With one 16KB bank, $8000 mirrors to $C000 and reads PRG offset 0.
        assertEquals(0xAB, cart.cpuRead(0x8000));
        assertEquals(0xAB, cart.cpuRead(0xC000));
    }
}
