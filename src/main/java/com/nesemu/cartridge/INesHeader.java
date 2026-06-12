package com.nesemu.cartridge;

/**
 * Parses and validates the 16-byte iNES (.nes) file header.
 *
 * iNES header layout:
 * <pre>
 *  Byte 0-3 : Constant "NES" followed by MS-DOS EOF (0x4E 0x45 0x53 0x1A)
 *  Byte 4   : Size of PRG ROM in 16 KB units
 *  Byte 5   : Size of CHR ROM in 8 KB units (0 means the board uses CHR RAM)
 *  Byte 6   : Flags 6  - mapper low nibble, mirroring, battery, trainer
 *  Byte 7   : Flags 7  - mapper high nibble, NES 2.0 identifier, console type
 *  Byte 8   : Flags 8  - PRG RAM size (rarely used in iNES)
 *  Byte 9-15: Flags 9-15 (mostly unused for our purposes)
 * </pre>
 */
public final class INesHeader {

    public static final int HEADER_SIZE = 16;
    public static final int PRG_BANK_SIZE = 16 * 1024; // 16 KB
    public static final int CHR_BANK_SIZE = 8 * 1024;  // 8 KB
    public static final int TRAINER_SIZE = 512;

    private final int prgRomBanks;
    private final int chrRomBanks;
    private final int mapperId;
    private final boolean hasTrainer;
    private final boolean hasBattery;
    private final boolean fourScreen;
    private final boolean verticalMirroring;

    private INesHeader(int prgRomBanks, int chrRomBanks, int mapperId,
                       boolean hasTrainer, boolean hasBattery,
                       boolean fourScreen, boolean verticalMirroring) {
        this.prgRomBanks = prgRomBanks;
        this.chrRomBanks = chrRomBanks;
        this.mapperId = mapperId;
        this.hasTrainer = hasTrainer;
        this.hasBattery = hasBattery;
        this.fourScreen = fourScreen;
        this.verticalMirroring = verticalMirroring;
    }

    /**
     * Parse an iNES header from the first 16 bytes of the given ROM image.
     *
     * @throws InvalidRomException if the magic number is missing or the file is too small.
     */
    public static INesHeader parse(byte[] rom) {
        if (rom == null || rom.length < HEADER_SIZE) {
            throw new InvalidRomException("File is too small to contain an iNES header.");
        }
        // Validate magic: "NES\u001A"
        if (rom[0] != 'N' || rom[1] != 'E' || rom[2] != 'S' || (rom[3] & 0xFF) != 0x1A) {
            throw new InvalidRomException("Not an iNES file (missing 'NES\\x1A' magic number).");
        }

        int prgRomBanks = rom[4] & 0xFF;
        int chrRomBanks = rom[5] & 0xFF;
        int flags6 = rom[6] & 0xFF;
        int flags7 = rom[7] & 0xFF;

        if (prgRomBanks == 0) {
            throw new InvalidRomException("ROM reports zero PRG-ROM banks.");
        }

        boolean verticalMirroring = (flags6 & 0x01) != 0;
        boolean hasBattery = (flags6 & 0x02) != 0;
        boolean hasTrainer = (flags6 & 0x04) != 0;
        boolean fourScreen = (flags6 & 0x08) != 0;

        int mapperLow = (flags6 >> 4) & 0x0F;
        int mapperHigh = (flags7 >> 4) & 0x0F;
        int mapperId = (mapperHigh << 4) | mapperLow;

        return new INesHeader(prgRomBanks, chrRomBanks, mapperId,
                hasTrainer, hasBattery, fourScreen, verticalMirroring);
    }

    public int getPrgRomBanks() { return prgRomBanks; }
    public int getChrRomBanks() { return chrRomBanks; }
    public int getPrgRomSize() { return prgRomBanks * PRG_BANK_SIZE; }
    public int getChrRomSize() { return chrRomBanks * CHR_BANK_SIZE; }
    public int getMapperId() { return mapperId; }
    public boolean hasTrainer() { return hasTrainer; }
    public boolean hasBattery() { return hasBattery; }
    public boolean isFourScreen() { return fourScreen; }
    public boolean isVerticalMirroring() { return verticalMirroring; }

    public Mirroring getMirroring() {
        if (fourScreen) {
            return Mirroring.FOUR_SCREEN;
        }
        return verticalMirroring ? Mirroring.VERTICAL : Mirroring.HORIZONTAL;
    }

    @Override
    public String toString() {
        return "iNES{prgBanks=" + prgRomBanks + " (" + getPrgRomSize() + "B)"
                + ", chrBanks=" + chrRomBanks + " (" + getChrRomSize() + "B)"
                + ", mapper=" + mapperId
                + ", mirroring=" + getMirroring()
                + ", trainer=" + hasTrainer
                + ", battery=" + hasBattery + "}";
    }
}
