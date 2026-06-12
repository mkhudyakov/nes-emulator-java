package com.nesemu.cartridge;

/**
 * Nametable mirroring arrangement determined by the cartridge.
 * NROM (Mapper 0) hard-wires this via a bit in the iNES header.
 * Super Mario Bros. uses VERTICAL mirroring (it scrolls horizontally).
 */
public enum Mirroring {
    HORIZONTAL,
    VERTICAL,
    SINGLE_SCREEN_LOWER,
    SINGLE_SCREEN_UPPER,
    FOUR_SCREEN
}
