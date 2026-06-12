package com.nesemu.cartridge;

import com.nesemu.mapper.Mapper;
import com.nesemu.mapper.Mapper0;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A loaded NES cartridge. Owns the PRG/CHR memory and the mapper, and exposes
 * the four access points the rest of the system needs: CPU read/write and
 * PPU read/write into the cartridge address spaces.
 */
public final class Cartridge {

    private final INesHeader header;
    private final Mapper mapper;

    private Cartridge(INesHeader header, Mapper mapper) {
        this.header = header;
        this.mapper = mapper;
    }

    /** Load a cartridge from a file on disk. */
    public static Cartridge fromFile(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        return fromBytes(data);
    }

    /** Load a cartridge from a raw iNES image already in memory. */
    public static Cartridge fromBytes(byte[] data) {
        INesHeader header = INesHeader.parse(data);

        int offset = INesHeader.HEADER_SIZE;
        if (header.hasTrainer()) {
            offset += INesHeader.TRAINER_SIZE; // skip the 512-byte trainer
        }

        int prgSize = header.getPrgRomSize();
        int chrSize = header.getChrRomSize();

        if (data.length < offset + prgSize) {
            throw new InvalidRomException(
                    "ROM file is truncated: expected at least " + (offset + prgSize)
                            + " bytes of PRG data but file is " + data.length + " bytes.");
        }

        byte[] prgRom = Arrays.copyOfRange(data, offset, offset + prgSize);
        offset += prgSize;

        boolean chrIsRam = (header.getChrRomBanks() == 0);
        byte[] chrMem;
        if (chrIsRam) {
            chrMem = new byte[8 * 1024]; // 8 KB CHR RAM
        } else {
            if (data.length < offset + chrSize) {
                throw new InvalidRomException(
                        "ROM file is truncated: missing CHR-ROM data.");
            }
            chrMem = Arrays.copyOfRange(data, offset, offset + chrSize);
        }

        Mapper mapper = createMapper(header, prgRom, chrMem, chrIsRam);
        return new Cartridge(header, mapper);
    }

    private static Mapper createMapper(INesHeader header, byte[] prgRom,
                                       byte[] chrMem, boolean chrIsRam) {
        int id = header.getMapperId();
        return switch (id) {
            case 0 -> new Mapper0(prgRom, chrMem,
                    header.getPrgRomBanks(),
                    header.getChrRomBanks(),
                    chrIsRam,
                    header.getMirroring());
            default -> throw new InvalidRomException(
                    "Unsupported mapper: " + id + ". This emulator currently only supports "
                            + "Mapper 0 (NROM).");
        };
    }

    // --- CPU side ($4020-$FFFF) -------------------------------------------

    /** @return value 0-255, or -1 if the cartridge does not decode this address. */
    public int cpuRead(int addr) {
        return mapper.cpuRead(addr);
    }

    public void cpuWrite(int addr, int value) {
        mapper.cpuWrite(addr, value);
    }

    // --- PPU side ($0000-$1FFF) -------------------------------------------

    public int ppuRead(int addr) {
        return mapper.ppuRead(addr);
    }

    public void ppuWrite(int addr, int value) {
        mapper.ppuWrite(addr, value);
    }

    public Mirroring getMirroring() {
        return mapper.getMirroring();
    }

    public INesHeader getHeader() {
        return header;
    }

    public Mapper getMapper() {
        return mapper;
    }

    public void reset() {
        mapper.reset();
    }
}
