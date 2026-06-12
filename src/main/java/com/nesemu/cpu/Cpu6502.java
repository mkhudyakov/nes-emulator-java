package com.nesemu.cpu;

/**
 * Emulates the Ricoh 2A03's 6502 core as used in the NES.
 *
 * <p>Notable NES specifics handled here:</p>
 * <ul>
 *   <li>Decimal mode is disabled (the 2A03 ignores the D flag in ADC/SBC).</li>
 *   <li>Reset, NMI and IRQ sequences with their proper vectors.</li>
 *   <li>The full official instruction set across all standard addressing modes.</li>
 * </ul>
 *
 * <p>The timing model is "instruction stepped with a cycle countdown": each call
 * to {@link #clock()} consumes one CPU cycle. When the countdown reaches zero the
 * next instruction is fetched and executed atomically, and the countdown is set to
 * that instruction's duration. This is not cycle-perfect (memory accesses happen
 * all at once rather than spread across the cycles) but is more than accurate
 * enough for Super Mario Bros. and similar NROM titles.</p>
 *
 * <p>The structure follows the well-known table-driven 6502 design popularised by
 * many open-source emulators.</p>
 */
public final class Cpu6502 {

    // Status register flag bit masks.
    public static final int FLAG_C = 1;       // Carry
    public static final int FLAG_Z = 1 << 1;  // Zero
    public static final int FLAG_I = 1 << 2;  // Interrupt disable
    public static final int FLAG_D = 1 << 3;  // Decimal (unused on NES)
    public static final int FLAG_B = 1 << 4;  // Break
    public static final int FLAG_U = 1 << 5;  // Unused (always pushed as 1)
    public static final int FLAG_V = 1 << 6;  // Overflow
    public static final int FLAG_N = 1 << 7;  // Negative

    private final Bus bus;

    // Registers
    private int a;       // accumulator (8-bit)
    private int x;       // X index (8-bit)
    private int y;       // Y index (8-bit)
    private int sp;      // stack pointer (8-bit, stack lives at $0100-$01FF)
    private int pc;      // program counter (16-bit)
    private int status;  // processor status (8-bit)

    // Execution scratch state
    private int fetched;     // value fetched for the current operation
    private int addrAbs;     // absolute effective address
    private int addrRel;     // signed relative offset for branches
    private int opcode;      // current opcode
    private int cycles;      // remaining cycles for the current instruction
    private boolean implied; // true when the current addressing mode is implied/accumulator
    private long totalCycles;// running total, useful for debugging/sync

    private final Instr[] lookup = new Instr[256];

    @FunctionalInterface
    private interface Micro {
        /** @return 1 if this step may require an extra cycle, else 0. */
        int run();
    }

    private record Instr(String name, Micro op, Micro addr, int cycles) {}

    public Cpu6502(Bus bus) {
        this.bus = bus;
        buildLookupTable();
    }

    // ------------------------------------------------------------------
    // Bus helpers
    // ------------------------------------------------------------------

    private int read(int addr) {
        return bus.cpuRead(addr & 0xFFFF) & 0xFF;
    }

    private void write(int addr, int value) {
        bus.cpuWrite(addr & 0xFFFF, value & 0xFF);
    }

    private void push(int value) {
        write(0x0100 + sp, value);
        sp = (sp - 1) & 0xFF;
    }

    private int pop() {
        sp = (sp + 1) & 0xFF;
        return read(0x0100 + sp);
    }

    // ------------------------------------------------------------------
    // Flag helpers
    // ------------------------------------------------------------------

    private boolean getFlag(int flag) {
        return (status & flag) != 0;
    }

    private void setFlag(int flag, boolean value) {
        if (value) {
            status |= flag;
        } else {
            status &= ~flag;
        }
        status &= 0xFF;
    }

    private void setZN(int value) {
        setFlag(FLAG_Z, (value & 0xFF) == 0);
        setFlag(FLAG_N, (value & 0x80) != 0);
    }

    private int fetch() {
        if (!implied) {
            fetched = read(addrAbs);
        }
        return fetched;
    }

    // ------------------------------------------------------------------
    // Interrupt and reset sequences
    // ------------------------------------------------------------------

    /** Power-on / reset. Loads PC from the reset vector at $FFFC. */
    public void reset() {
        int lo = read(0xFFFC);
        int hi = read(0xFFFD);
        pc = (hi << 8) | lo;

        a = 0;
        x = 0;
        y = 0;
        sp = 0xFD;
        status = FLAG_U | FLAG_I; // 0x24

        fetched = 0;
        addrAbs = 0;
        addrRel = 0;
        implied = false;
        cycles = 8;
    }

    /** Maskable interrupt. Ignored if the I flag is set. */
    public void irq() {
        if (getFlag(FLAG_I)) {
            return;
        }
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        setFlag(FLAG_B, false);
        setFlag(FLAG_U, true);
        setFlag(FLAG_I, true);
        push(status);
        int lo = read(0xFFFE);
        int hi = read(0xFFFF);
        pc = (hi << 8) | lo;
        cycles = 7;
    }

    /** Non-maskable interrupt. Triggered by the PPU at the start of VBlank. */
    public void nmi() {
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        setFlag(FLAG_B, false);
        setFlag(FLAG_U, true);
        setFlag(FLAG_I, true);
        push(status);
        int lo = read(0xFFFA);
        int hi = read(0xFFFB);
        pc = (hi << 8) | lo;
        cycles = 8;
    }

    // ------------------------------------------------------------------
    // Stepping
    // ------------------------------------------------------------------

    /** Advance the CPU by one clock cycle. */
    public void clock() {
        if (cycles == 0) {
            executeInstruction();
        }
        cycles--;
        totalCycles++;
    }

    /** True when the current instruction (if any) has finished executing. */
    public boolean isInstructionComplete() {
        return cycles == 0;
    }

    /**
     * Execute exactly one full instruction immediately and return the number of
     * cycles it nominally takes. Intended for unit tests and the step debugger;
     * the main system loop uses {@link #clock()} instead.
     */
    public int stepInstruction() {
        executeInstruction();
        int used = cycles;
        cycles = 0;
        totalCycles += used;
        return used;
    }

    private void executeInstruction() {
        opcode = read(pc);
        pc = (pc + 1) & 0xFFFF;
        setFlag(FLAG_U, true);

        Instr instr = lookup[opcode];
        cycles = instr.cycles();
        implied = false;

        int extraAddr = instr.addr().run();
        int extraOp = instr.op().run();
        cycles += (extraAddr & extraOp);

        setFlag(FLAG_U, true);
    }

    // ------------------------------------------------------------------
    // Addressing modes (each returns 1 if a page-cross may add a cycle)
    // ------------------------------------------------------------------

    private int IMP() { // implied & accumulator
        implied = true;
        fetched = a;
        return 0;
    }

    private int IMM() {
        addrAbs = pc;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int ZP0() {
        addrAbs = read(pc) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int ZPX() {
        addrAbs = (read(pc) + x) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int ZPY() {
        addrAbs = (read(pc) + y) & 0xFF;
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int REL() {
        int offset = read(pc);
        pc = (pc + 1) & 0xFFFF;
        if ((offset & 0x80) != 0) {
            offset -= 0x100; // sign-extend
        }
        addrRel = offset;
        return 0;
    }

    private int ABS() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        addrAbs = (hi << 8) | lo;
        return 0;
    }

    private int ABX() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int base = (hi << 8) | lo;
        addrAbs = (base + x) & 0xFFFF;
        return ((addrAbs & 0xFF00) != (hi << 8)) ? 1 : 0;
    }

    private int ABY() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int base = (hi << 8) | lo;
        addrAbs = (base + y) & 0xFFFF;
        return ((addrAbs & 0xFF00) != (hi << 8)) ? 1 : 0;
    }

    private int IND() {
        int lo = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int hi = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int ptr = (hi << 8) | lo;
        // Reproduce the original 6502 page-boundary bug for JMP (indirect).
        if (lo == 0x00FF) {
            addrAbs = (read(ptr & 0xFF00) << 8) | read(ptr);
        } else {
            addrAbs = (read(ptr + 1) << 8) | read(ptr);
        }
        return 0;
    }

    private int IZX() {
        int t = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int lo = read((t + x) & 0xFF);
        int hi = read((t + x + 1) & 0xFF);
        addrAbs = (hi << 8) | lo;
        return 0;
    }

    private int IZY() {
        int t = read(pc);
        pc = (pc + 1) & 0xFFFF;
        int lo = read(t & 0xFF);
        int hi = read((t + 1) & 0xFF);
        int base = (hi << 8) | lo;
        addrAbs = (base + y) & 0xFFFF;
        return ((addrAbs & 0xFF00) != (hi << 8)) ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // Operations (each returns 1 if it can take an extra cycle on page cross)
    // ------------------------------------------------------------------

    private int branch(boolean condition) {
        if (condition) {
            cycles++;
            addrAbs = (pc + addrRel) & 0xFFFF;
            if ((addrAbs & 0xFF00) != (pc & 0xFF00)) {
                cycles++;
            }
            pc = addrAbs;
        }
        return 0;
    }

    private int ADC() {
        fetch();
        int temp = a + fetched + (getFlag(FLAG_C) ? 1 : 0);
        setFlag(FLAG_C, temp > 0xFF);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_V, ((~(a ^ fetched) & (a ^ temp)) & 0x80) != 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        a = temp & 0xFF;
        return 1;
    }

    private int SBC() {
        fetch();
        int value = fetched ^ 0xFF;
        int temp = a + value + (getFlag(FLAG_C) ? 1 : 0);
        setFlag(FLAG_C, (temp & 0xFF00) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_V, ((temp ^ a) & (temp ^ value) & 0x80) != 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        a = temp & 0xFF;
        return 1;
    }

    private int AND() {
        fetch();
        a = (a & fetched) & 0xFF;
        setZN(a);
        return 1;
    }

    private int ORA() {
        fetch();
        a = (a | fetched) & 0xFF;
        setZN(a);
        return 1;
    }

    private int EOR() {
        fetch();
        a = (a ^ fetched) & 0xFF;
        setZN(a);
        return 1;
    }

    private int ASL() {
        fetch();
        int temp = fetched << 1;
        setFlag(FLAG_C, (temp & 0xFF00) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (implied) {
            a = temp & 0xFF;
        } else {
            write(addrAbs, temp & 0xFF);
        }
        return 0;
    }

    private int LSR() {
        fetch();
        setFlag(FLAG_C, (fetched & 0x01) != 0);
        int temp = fetched >> 1;
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (implied) {
            a = temp & 0xFF;
        } else {
            write(addrAbs, temp & 0xFF);
        }
        return 0;
    }

    private int ROL() {
        fetch();
        int temp = (fetched << 1) | (getFlag(FLAG_C) ? 1 : 0);
        setFlag(FLAG_C, (temp & 0xFF00) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (implied) {
            a = temp & 0xFF;
        } else {
            write(addrAbs, temp & 0xFF);
        }
        return 0;
    }

    private int ROR() {
        fetch();
        int temp = (getFlag(FLAG_C) ? 0x80 : 0x00) | (fetched >> 1);
        setFlag(FLAG_C, (fetched & 0x01) != 0);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        if (implied) {
            a = temp & 0xFF;
        } else {
            write(addrAbs, temp & 0xFF);
        }
        return 0;
    }

    private int BIT() {
        fetch();
        int temp = a & fetched;
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (fetched & 0x80) != 0);
        setFlag(FLAG_V, (fetched & 0x40) != 0);
        return 0;
    }

    private int CMP() {
        fetch();
        int temp = a - fetched;
        setFlag(FLAG_C, a >= fetched);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 1;
    }

    private int CPX() {
        fetch();
        int temp = x - fetched;
        setFlag(FLAG_C, x >= fetched);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 0;
    }

    private int CPY() {
        fetch();
        int temp = y - fetched;
        setFlag(FLAG_C, y >= fetched);
        setFlag(FLAG_Z, (temp & 0xFF) == 0);
        setFlag(FLAG_N, (temp & 0x80) != 0);
        return 0;
    }

    private int DEC() {
        fetch();
        int temp = (fetched - 1) & 0xFF;
        write(addrAbs, temp);
        setZN(temp);
        return 0;
    }

    private int INC() {
        fetch();
        int temp = (fetched + 1) & 0xFF;
        write(addrAbs, temp);
        setZN(temp);
        return 0;
    }

    private int DEX() { x = (x - 1) & 0xFF; setZN(x); return 0; }
    private int DEY() { y = (y - 1) & 0xFF; setZN(y); return 0; }
    private int INX() { x = (x + 1) & 0xFF; setZN(x); return 0; }
    private int INY() { y = (y + 1) & 0xFF; setZN(y); return 0; }

    private int LDA() { fetch(); a = fetched; setZN(a); return 1; }
    private int LDX() { fetch(); x = fetched; setZN(x); return 1; }
    private int LDY() { fetch(); y = fetched; setZN(y); return 1; }

    private int STA() { write(addrAbs, a); return 0; }
    private int STX() { write(addrAbs, x); return 0; }
    private int STY() { write(addrAbs, y); return 0; }

    private int TAX() { x = a; setZN(x); return 0; }
    private int TAY() { y = a; setZN(y); return 0; }
    private int TXA() { a = x; setZN(a); return 0; }
    private int TYA() { a = y; setZN(a); return 0; }
    private int TSX() { x = sp; setZN(x); return 0; }
    private int TXS() { sp = x; return 0; }

    private int PHA() { push(a); return 0; }

    private int PLA() {
        a = pop();
        setZN(a);
        return 0;
    }

    private int PHP() {
        push(status | FLAG_B | FLAG_U);
        setFlag(FLAG_B, false);
        setFlag(FLAG_U, false);
        return 0;
    }

    private int PLP() {
        status = pop();
        setFlag(FLAG_U, true);
        return 0;
    }

    private int JMP() {
        pc = addrAbs;
        return 0;
    }

    private int JSR() {
        pc = (pc - 1) & 0xFFFF;
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        pc = addrAbs;
        return 0;
    }

    private int RTS() {
        int lo = pop();
        int hi = pop();
        pc = ((hi << 8) | lo);
        pc = (pc + 1) & 0xFFFF;
        return 0;
    }

    private int RTI() {
        status = pop();
        status &= ~FLAG_B;
        status &= ~FLAG_U;
        int lo = pop();
        int hi = pop();
        pc = (hi << 8) | lo;
        return 0;
    }

    private int BRK() {
        pc = (pc + 1) & 0xFFFF; // BRK skips the following padding byte
        setFlag(FLAG_I, true);
        push((pc >> 8) & 0xFF);
        push(pc & 0xFF);
        push(status | FLAG_B | FLAG_U);
        setFlag(FLAG_B, false);
        int lo = read(0xFFFE);
        int hi = read(0xFFFF);
        pc = (hi << 8) | lo;
        return 0;
    }

    private int CLC() { setFlag(FLAG_C, false); return 0; }
    private int SEC() { setFlag(FLAG_C, true); return 0; }
    private int CLD() { setFlag(FLAG_D, false); return 0; }
    private int SED() { setFlag(FLAG_D, true); return 0; }
    private int CLI() { setFlag(FLAG_I, false); return 0; }
    private int SEI() { setFlag(FLAG_I, true); return 0; }
    private int CLV() { setFlag(FLAG_V, false); return 0; }

    private int BCC() { return branch(!getFlag(FLAG_C)); }
    private int BCS() { return branch(getFlag(FLAG_C)); }
    private int BEQ() { return branch(getFlag(FLAG_Z)); }
    private int BNE() { return branch(!getFlag(FLAG_Z)); }
    private int BMI() { return branch(getFlag(FLAG_N)); }
    private int BPL() { return branch(!getFlag(FLAG_N)); }
    private int BVS() { return branch(getFlag(FLAG_V)); }
    private int BVC() { return branch(!getFlag(FLAG_V)); }

    private int NOP() { return 0; }

    /** Catch-all for unofficial opcodes we do not implement; behaves like NOP. */
    private int XXX() { return 0; }

    // ------------------------------------------------------------------
    // Opcode table
    // ------------------------------------------------------------------

    private void buildLookupTable() {
        // Helper to assign an opcode.
        // The 16x16 matrix below mirrors the canonical 6502 opcode map.
        int i = 0;
        Instr[] t = lookup;

        t[0x00] = new Instr("BRK", this::BRK, this::IMM, 7);
        t[0x01] = new Instr("ORA", this::ORA, this::IZX, 6);
        t[0x02] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x03] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x04] = new Instr("???", this::NOP, this::IMP, 3);
        t[0x05] = new Instr("ORA", this::ORA, this::ZP0, 3);
        t[0x06] = new Instr("ASL", this::ASL, this::ZP0, 5);
        t[0x07] = new Instr("???", this::XXX, this::IMP, 5);
        t[0x08] = new Instr("PHP", this::PHP, this::IMP, 3);
        t[0x09] = new Instr("ORA", this::ORA, this::IMM, 2);
        t[0x0A] = new Instr("ASL", this::ASL, this::IMP, 2);
        t[0x0B] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x0C] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x0D] = new Instr("ORA", this::ORA, this::ABS, 4);
        t[0x0E] = new Instr("ASL", this::ASL, this::ABS, 6);
        t[0x0F] = new Instr("???", this::XXX, this::IMP, 6);

        t[0x10] = new Instr("BPL", this::BPL, this::REL, 2);
        t[0x11] = new Instr("ORA", this::ORA, this::IZY, 5);
        t[0x12] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x13] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x14] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x15] = new Instr("ORA", this::ORA, this::ZPX, 4);
        t[0x16] = new Instr("ASL", this::ASL, this::ZPX, 6);
        t[0x17] = new Instr("???", this::XXX, this::IMP, 6);
        t[0x18] = new Instr("CLC", this::CLC, this::IMP, 2);
        t[0x19] = new Instr("ORA", this::ORA, this::ABY, 4);
        t[0x1A] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x1B] = new Instr("???", this::XXX, this::IMP, 7);
        t[0x1C] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x1D] = new Instr("ORA", this::ORA, this::ABX, 4);
        t[0x1E] = new Instr("ASL", this::ASL, this::ABX, 7);
        t[0x1F] = new Instr("???", this::XXX, this::IMP, 7);

        t[0x20] = new Instr("JSR", this::JSR, this::ABS, 6);
        t[0x21] = new Instr("AND", this::AND, this::IZX, 6);
        t[0x22] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x23] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x24] = new Instr("BIT", this::BIT, this::ZP0, 3);
        t[0x25] = new Instr("AND", this::AND, this::ZP0, 3);
        t[0x26] = new Instr("ROL", this::ROL, this::ZP0, 5);
        t[0x27] = new Instr("???", this::XXX, this::IMP, 5);
        t[0x28] = new Instr("PLP", this::PLP, this::IMP, 4);
        t[0x29] = new Instr("AND", this::AND, this::IMM, 2);
        t[0x2A] = new Instr("ROL", this::ROL, this::IMP, 2);
        t[0x2B] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x2C] = new Instr("BIT", this::BIT, this::ABS, 4);
        t[0x2D] = new Instr("AND", this::AND, this::ABS, 4);
        t[0x2E] = new Instr("ROL", this::ROL, this::ABS, 6);
        t[0x2F] = new Instr("???", this::XXX, this::IMP, 6);

        t[0x30] = new Instr("BMI", this::BMI, this::REL, 2);
        t[0x31] = new Instr("AND", this::AND, this::IZY, 5);
        t[0x32] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x33] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x34] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x35] = new Instr("AND", this::AND, this::ZPX, 4);
        t[0x36] = new Instr("ROL", this::ROL, this::ZPX, 6);
        t[0x37] = new Instr("???", this::XXX, this::IMP, 6);
        t[0x38] = new Instr("SEC", this::SEC, this::IMP, 2);
        t[0x39] = new Instr("AND", this::AND, this::ABY, 4);
        t[0x3A] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x3B] = new Instr("???", this::XXX, this::IMP, 7);
        t[0x3C] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x3D] = new Instr("AND", this::AND, this::ABX, 4);
        t[0x3E] = new Instr("ROL", this::ROL, this::ABX, 7);
        t[0x3F] = new Instr("???", this::XXX, this::IMP, 7);

        t[0x40] = new Instr("RTI", this::RTI, this::IMP, 6);
        t[0x41] = new Instr("EOR", this::EOR, this::IZX, 6);
        t[0x42] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x43] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x44] = new Instr("???", this::NOP, this::IMP, 3);
        t[0x45] = new Instr("EOR", this::EOR, this::ZP0, 3);
        t[0x46] = new Instr("LSR", this::LSR, this::ZP0, 5);
        t[0x47] = new Instr("???", this::XXX, this::IMP, 5);
        t[0x48] = new Instr("PHA", this::PHA, this::IMP, 3);
        t[0x49] = new Instr("EOR", this::EOR, this::IMM, 2);
        t[0x4A] = new Instr("LSR", this::LSR, this::IMP, 2);
        t[0x4B] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x4C] = new Instr("JMP", this::JMP, this::ABS, 3);
        t[0x4D] = new Instr("EOR", this::EOR, this::ABS, 4);
        t[0x4E] = new Instr("LSR", this::LSR, this::ABS, 6);
        t[0x4F] = new Instr("???", this::XXX, this::IMP, 6);

        t[0x50] = new Instr("BVC", this::BVC, this::REL, 2);
        t[0x51] = new Instr("EOR", this::EOR, this::IZY, 5);
        t[0x52] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x53] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x54] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x55] = new Instr("EOR", this::EOR, this::ZPX, 4);
        t[0x56] = new Instr("LSR", this::LSR, this::ZPX, 6);
        t[0x57] = new Instr("???", this::XXX, this::IMP, 6);
        t[0x58] = new Instr("CLI", this::CLI, this::IMP, 2);
        t[0x59] = new Instr("EOR", this::EOR, this::ABY, 4);
        t[0x5A] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x5B] = new Instr("???", this::XXX, this::IMP, 7);
        t[0x5C] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x5D] = new Instr("EOR", this::EOR, this::ABX, 4);
        t[0x5E] = new Instr("LSR", this::LSR, this::ABX, 7);
        t[0x5F] = new Instr("???", this::XXX, this::IMP, 7);

        t[0x60] = new Instr("RTS", this::RTS, this::IMP, 6);
        t[0x61] = new Instr("ADC", this::ADC, this::IZX, 6);
        t[0x62] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x63] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x64] = new Instr("???", this::NOP, this::IMP, 3);
        t[0x65] = new Instr("ADC", this::ADC, this::ZP0, 3);
        t[0x66] = new Instr("ROR", this::ROR, this::ZP0, 5);
        t[0x67] = new Instr("???", this::XXX, this::IMP, 5);
        t[0x68] = new Instr("PLA", this::PLA, this::IMP, 4);
        t[0x69] = new Instr("ADC", this::ADC, this::IMM, 2);
        t[0x6A] = new Instr("ROR", this::ROR, this::IMP, 2);
        t[0x6B] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x6C] = new Instr("JMP", this::JMP, this::IND, 5);
        t[0x6D] = new Instr("ADC", this::ADC, this::ABS, 4);
        t[0x6E] = new Instr("ROR", this::ROR, this::ABS, 6);
        t[0x6F] = new Instr("???", this::XXX, this::IMP, 6);

        t[0x70] = new Instr("BVS", this::BVS, this::REL, 2);
        t[0x71] = new Instr("ADC", this::ADC, this::IZY, 5);
        t[0x72] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x73] = new Instr("???", this::XXX, this::IMP, 8);
        t[0x74] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x75] = new Instr("ADC", this::ADC, this::ZPX, 4);
        t[0x76] = new Instr("ROR", this::ROR, this::ZPX, 6);
        t[0x77] = new Instr("???", this::XXX, this::IMP, 6);
        t[0x78] = new Instr("SEI", this::SEI, this::IMP, 2);
        t[0x79] = new Instr("ADC", this::ADC, this::ABY, 4);
        t[0x7A] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x7B] = new Instr("???", this::XXX, this::IMP, 7);
        t[0x7C] = new Instr("???", this::NOP, this::IMP, 4);
        t[0x7D] = new Instr("ADC", this::ADC, this::ABX, 4);
        t[0x7E] = new Instr("ROR", this::ROR, this::ABX, 7);
        t[0x7F] = new Instr("???", this::XXX, this::IMP, 7);

        t[0x80] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x81] = new Instr("STA", this::STA, this::IZX, 6);
        t[0x82] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x83] = new Instr("???", this::XXX, this::IMP, 6);
        t[0x84] = new Instr("STY", this::STY, this::ZP0, 3);
        t[0x85] = new Instr("STA", this::STA, this::ZP0, 3);
        t[0x86] = new Instr("STX", this::STX, this::ZP0, 3);
        t[0x87] = new Instr("???", this::XXX, this::IMP, 3);
        t[0x88] = new Instr("DEY", this::DEY, this::IMP, 2);
        t[0x89] = new Instr("???", this::NOP, this::IMP, 2);
        t[0x8A] = new Instr("TXA", this::TXA, this::IMP, 2);
        t[0x8B] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x8C] = new Instr("STY", this::STY, this::ABS, 4);
        t[0x8D] = new Instr("STA", this::STA, this::ABS, 4);
        t[0x8E] = new Instr("STX", this::STX, this::ABS, 4);
        t[0x8F] = new Instr("???", this::XXX, this::IMP, 4);

        t[0x90] = new Instr("BCC", this::BCC, this::REL, 2);
        t[0x91] = new Instr("STA", this::STA, this::IZY, 6);
        t[0x92] = new Instr("???", this::XXX, this::IMP, 2);
        t[0x93] = new Instr("???", this::XXX, this::IMP, 6);
        t[0x94] = new Instr("STY", this::STY, this::ZPX, 4);
        t[0x95] = new Instr("STA", this::STA, this::ZPX, 4);
        t[0x96] = new Instr("STX", this::STX, this::ZPY, 4);
        t[0x97] = new Instr("???", this::XXX, this::IMP, 4);
        t[0x98] = new Instr("TYA", this::TYA, this::IMP, 2);
        t[0x99] = new Instr("STA", this::STA, this::ABY, 5);
        t[0x9A] = new Instr("TXS", this::TXS, this::IMP, 2);
        t[0x9B] = new Instr("???", this::XXX, this::IMP, 5);
        t[0x9C] = new Instr("???", this::NOP, this::IMP, 5);
        t[0x9D] = new Instr("STA", this::STA, this::ABX, 5);
        t[0x9E] = new Instr("???", this::XXX, this::IMP, 5);
        t[0x9F] = new Instr("???", this::XXX, this::IMP, 5);

        t[0xA0] = new Instr("LDY", this::LDY, this::IMM, 2);
        t[0xA1] = new Instr("LDA", this::LDA, this::IZX, 6);
        t[0xA2] = new Instr("LDX", this::LDX, this::IMM, 2);
        t[0xA3] = new Instr("???", this::XXX, this::IMP, 6);
        t[0xA4] = new Instr("LDY", this::LDY, this::ZP0, 3);
        t[0xA5] = new Instr("LDA", this::LDA, this::ZP0, 3);
        t[0xA6] = new Instr("LDX", this::LDX, this::ZP0, 3);
        t[0xA7] = new Instr("???", this::XXX, this::IMP, 3);
        t[0xA8] = new Instr("TAY", this::TAY, this::IMP, 2);
        t[0xA9] = new Instr("LDA", this::LDA, this::IMM, 2);
        t[0xAA] = new Instr("TAX", this::TAX, this::IMP, 2);
        t[0xAB] = new Instr("???", this::XXX, this::IMP, 2);
        t[0xAC] = new Instr("LDY", this::LDY, this::ABS, 4);
        t[0xAD] = new Instr("LDA", this::LDA, this::ABS, 4);
        t[0xAE] = new Instr("LDX", this::LDX, this::ABS, 4);
        t[0xAF] = new Instr("???", this::XXX, this::IMP, 4);

        t[0xB0] = new Instr("BCS", this::BCS, this::REL, 2);
        t[0xB1] = new Instr("LDA", this::LDA, this::IZY, 5);
        t[0xB2] = new Instr("???", this::XXX, this::IMP, 2);
        t[0xB3] = new Instr("???", this::XXX, this::IMP, 5);
        t[0xB4] = new Instr("LDY", this::LDY, this::ZPX, 4);
        t[0xB5] = new Instr("LDA", this::LDA, this::ZPX, 4);
        t[0xB6] = new Instr("LDX", this::LDX, this::ZPY, 4);
        t[0xB7] = new Instr("???", this::XXX, this::IMP, 4);
        t[0xB8] = new Instr("CLV", this::CLV, this::IMP, 2);
        t[0xB9] = new Instr("LDA", this::LDA, this::ABY, 4);
        t[0xBA] = new Instr("TSX", this::TSX, this::IMP, 2);
        t[0xBB] = new Instr("???", this::XXX, this::IMP, 4);
        t[0xBC] = new Instr("LDY", this::LDY, this::ABX, 4);
        t[0xBD] = new Instr("LDA", this::LDA, this::ABX, 4);
        t[0xBE] = new Instr("LDX", this::LDX, this::ABY, 4);
        t[0xBF] = new Instr("???", this::XXX, this::IMP, 4);

        t[0xC0] = new Instr("CPY", this::CPY, this::IMM, 2);
        t[0xC1] = new Instr("CMP", this::CMP, this::IZX, 6);
        t[0xC2] = new Instr("???", this::NOP, this::IMP, 2);
        t[0xC3] = new Instr("???", this::XXX, this::IMP, 8);
        t[0xC4] = new Instr("CPY", this::CPY, this::ZP0, 3);
        t[0xC5] = new Instr("CMP", this::CMP, this::ZP0, 3);
        t[0xC6] = new Instr("DEC", this::DEC, this::ZP0, 5);
        t[0xC7] = new Instr("???", this::XXX, this::IMP, 5);
        t[0xC8] = new Instr("INY", this::INY, this::IMP, 2);
        t[0xC9] = new Instr("CMP", this::CMP, this::IMM, 2);
        t[0xCA] = new Instr("DEX", this::DEX, this::IMP, 2);
        t[0xCB] = new Instr("???", this::XXX, this::IMP, 2);
        t[0xCC] = new Instr("CPY", this::CPY, this::ABS, 4);
        t[0xCD] = new Instr("CMP", this::CMP, this::ABS, 4);
        t[0xCE] = new Instr("DEC", this::DEC, this::ABS, 6);
        t[0xCF] = new Instr("???", this::XXX, this::IMP, 6);

        t[0xD0] = new Instr("BNE", this::BNE, this::REL, 2);
        t[0xD1] = new Instr("CMP", this::CMP, this::IZY, 5);
        t[0xD2] = new Instr("???", this::XXX, this::IMP, 2);
        t[0xD3] = new Instr("???", this::XXX, this::IMP, 8);
        t[0xD4] = new Instr("???", this::NOP, this::IMP, 4);
        t[0xD5] = new Instr("CMP", this::CMP, this::ZPX, 4);
        t[0xD6] = new Instr("DEC", this::DEC, this::ZPX, 6);
        t[0xD7] = new Instr("???", this::XXX, this::IMP, 6);
        t[0xD8] = new Instr("CLD", this::CLD, this::IMP, 2);
        t[0xD9] = new Instr("CMP", this::CMP, this::ABY, 4);
        t[0xDA] = new Instr("???", this::NOP, this::IMP, 2);
        t[0xDB] = new Instr("???", this::XXX, this::IMP, 7);
        t[0xDC] = new Instr("???", this::NOP, this::IMP, 4);
        t[0xDD] = new Instr("CMP", this::CMP, this::ABX, 4);
        t[0xDE] = new Instr("DEC", this::DEC, this::ABX, 7);
        t[0xDF] = new Instr("???", this::XXX, this::IMP, 7);

        t[0xE0] = new Instr("CPX", this::CPX, this::IMM, 2);
        t[0xE1] = new Instr("SBC", this::SBC, this::IZX, 6);
        t[0xE2] = new Instr("???", this::NOP, this::IMP, 2);
        t[0xE3] = new Instr("???", this::XXX, this::IMP, 8);
        t[0xE4] = new Instr("CPX", this::CPX, this::ZP0, 3);
        t[0xE5] = new Instr("SBC", this::SBC, this::ZP0, 3);
        t[0xE6] = new Instr("INC", this::INC, this::ZP0, 5);
        t[0xE7] = new Instr("???", this::XXX, this::IMP, 5);
        t[0xE8] = new Instr("INX", this::INX, this::IMP, 2);
        t[0xE9] = new Instr("SBC", this::SBC, this::IMM, 2);
        t[0xEA] = new Instr("NOP", this::NOP, this::IMP, 2);
        t[0xEB] = new Instr("???", this::SBC, this::IMP, 2); // unofficial SBC, same as legal
        t[0xEC] = new Instr("CPX", this::CPX, this::ABS, 4);
        t[0xED] = new Instr("SBC", this::SBC, this::ABS, 4);
        t[0xEE] = new Instr("INC", this::INC, this::ABS, 6);
        t[0xEF] = new Instr("???", this::XXX, this::IMP, 6);

        t[0xF0] = new Instr("BEQ", this::BEQ, this::REL, 2);
        t[0xF1] = new Instr("SBC", this::SBC, this::IZY, 5);
        t[0xF2] = new Instr("???", this::XXX, this::IMP, 2);
        t[0xF3] = new Instr("???", this::XXX, this::IMP, 8);
        t[0xF4] = new Instr("???", this::NOP, this::IMP, 4);
        t[0xF5] = new Instr("SBC", this::SBC, this::ZPX, 4);
        t[0xF6] = new Instr("INC", this::INC, this::ZPX, 6);
        t[0xF7] = new Instr("???", this::XXX, this::IMP, 6);
        t[0xF8] = new Instr("SED", this::SED, this::IMP, 2);
        t[0xF9] = new Instr("SBC", this::SBC, this::ABY, 4);
        t[0xFA] = new Instr("???", this::NOP, this::IMP, 2);
        t[0xFB] = new Instr("???", this::XXX, this::IMP, 7);
        t[0xFC] = new Instr("???", this::NOP, this::IMP, 4);
        t[0xFD] = new Instr("SBC", this::SBC, this::ABX, 4);
        t[0xFE] = new Instr("INC", this::INC, this::ABX, 7);
        t[0xFF] = new Instr("???", this::XXX, this::IMP, 7);

        // Sanity: ensure every slot is populated.
        for (i = 0; i < 256; i++) {
            if (t[i] == null) {
                t[i] = new Instr("???", this::XXX, this::IMP, 2);
            }
        }
    }

    // ------------------------------------------------------------------
    // Accessors (mainly for tests, the debugger and system wiring)
    // ------------------------------------------------------------------

    public int getA() { return a; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getSp() { return sp; }
    public int getPc() { return pc; }
    public int getStatus() { return status; }
    public int getRemainingCycles() { return cycles; }
    public long getTotalCycles() { return totalCycles; }
    public String getOpcodeName(int op) { return lookup[op & 0xFF].name(); }

    public void setPc(int value) { this.pc = value & 0xFFFF; }
    public void setA(int value) { this.a = value & 0xFF; }
    public void setX(int value) { this.x = value & 0xFF; }
    public void setY(int value) { this.y = value & 0xFF; }
    public void setSp(int value) { this.sp = value & 0xFF; }
    public void setStatus(int value) { this.status = value & 0xFF; }
    public boolean getFlagPublic(int flag) { return getFlag(flag); }
}
