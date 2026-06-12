package com.nesemu.cpu;

/**
 * The interface the CPU uses to talk to the rest of the machine. Implemented by
 * {@code NesSystem}, which routes addresses to RAM, the PPU, controllers and
 * the cartridge.
 */
public interface Bus {
    /** Read a byte (0-255) from the CPU address space. */
    int cpuRead(int addr);

    /** Write a byte (0-255) to the CPU address space. */
    void cpuWrite(int addr, int value);
}
