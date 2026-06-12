package com.nesemu.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Cpu6502Test {

    /** A flat 64KB memory used as the CPU bus for testing. */
    private int[] mem;
    private Cpu6502 cpu;

    @BeforeEach
    void setUp() {
        mem = new int[0x10000];
        Bus bus = new Bus() {
            @Override
            public int cpuRead(int addr) {
                return mem[addr & 0xFFFF] & 0xFF;
            }

            @Override
            public void cpuWrite(int addr, int value) {
                mem[addr & 0xFFFF] = value & 0xFF;
            }
        };
        cpu = new Cpu6502(bus);
    }

    /** Point the reset vector at $8000 and reset the CPU there. */
    private void resetTo(int pc) {
        mem[0xFFFC] = pc & 0xFF;
        mem[0xFFFD] = (pc >> 8) & 0xFF;
        cpu.reset();
    }

    /** Load bytes into memory starting at the given address. */
    private void load(int addr, int... bytes) {
        for (int i = 0; i < bytes.length; i++) {
            mem[(addr + i) & 0xFFFF] = bytes[i] & 0xFF;
        }
    }

    @Test
    void resetLoadsProgramCounterFromVector() {
        resetTo(0x8000);
        assertEquals(0x8000, cpu.getPc());
        assertEquals(0xFD, cpu.getSp());
        // I and U flags set after reset (0x24).
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_I));
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_U));
    }

    @Test
    void ldaImmediateSetsAccumulatorAndZeroFlag() {
        resetTo(0x8000);
        load(0x8000, 0xA9, 0x00); // LDA #$00
        cpu.stepInstruction();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_Z));
        assertFalse(cpu.getFlagPublic(Cpu6502.FLAG_N));
    }

    @Test
    void ldaImmediateSetsNegativeFlag() {
        resetTo(0x8000);
        load(0x8000, 0xA9, 0x80); // LDA #$80
        cpu.stepInstruction();
        assertEquals(0x80, cpu.getA());
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_N));
        assertFalse(cpu.getFlagPublic(Cpu6502.FLAG_Z));
    }

    @Test
    void staZeroPageWritesMemory() {
        resetTo(0x8000);
        load(0x8000, 0xA9, 0x37, 0x85, 0x10); // LDA #$37 ; STA $10
        cpu.stepInstruction();
        cpu.stepInstruction();
        assertEquals(0x37, mem[0x0010]);
    }

    @Test
    void adcAddsWithCarryOut() {
        resetTo(0x8000);
        load(0x8000, 0xA9, 0xFF, 0x69, 0x01); // LDA #$FF ; ADC #$01
        cpu.stepInstruction();
        cpu.stepInstruction();
        assertEquals(0x00, cpu.getA());
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_C));
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_Z));
    }

    @Test
    void adcSetsOverflowOnSignedWrap() {
        resetTo(0x8000);
        load(0x8000, 0xA9, 0x7F, 0x69, 0x01); // LDA #$7F ; ADC #$01 => 0x80
        cpu.stepInstruction();
        cpu.stepInstruction();
        assertEquals(0x80, cpu.getA());
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_V));
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_N));
    }

    @Test
    void inxWrapsAround() {
        resetTo(0x8000);
        load(0x8000, 0xA2, 0xFF, 0xE8); // LDX #$FF ; INX
        cpu.stepInstruction();
        cpu.stepInstruction();
        assertEquals(0x00, cpu.getX());
        assertTrue(cpu.getFlagPublic(Cpu6502.FLAG_Z));
    }

    @Test
    void branchTakenWhenZeroSet() {
        resetTo(0x8000);
        // LDA #$00 (sets Z) ; BEQ +2 ; LDA #$EE (skipped) ; LDA #$11
        load(0x8000, 0xA9, 0x00, 0xF0, 0x02, 0xA9, 0xEE, 0xA9, 0x11);
        cpu.stepInstruction(); // LDA #$00
        cpu.stepInstruction(); // BEQ +2 -> jumps over the LDA #$EE
        cpu.stepInstruction(); // LDA #$11
        assertEquals(0x11, cpu.getA());
    }

    @Test
    void jsrAndRtsReturnToCaller() {
        resetTo(0x8000);
        // JSR $8005 ; (return here) LDA #$01 ; at $8005: LDA #$09 ; RTS
        load(0x8000, 0x20, 0x05, 0x80, 0xA9, 0x01);
        load(0x8005, 0xA9, 0x09, 0x60);
        cpu.stepInstruction(); // JSR
        assertEquals(0x8005, cpu.getPc());
        cpu.stepInstruction(); // LDA #$09
        assertEquals(0x09, cpu.getA());
        cpu.stepInstruction(); // RTS
        assertEquals(0x8003, cpu.getPc());
        cpu.stepInstruction(); // LDA #$01
        assertEquals(0x01, cpu.getA());
    }
}
