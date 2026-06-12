# How a Computer Works — A Guided Tour Through This NES Emulator

This document teaches you how a real computer is built, using this emulator as a
working specimen you can read, modify, and run. The Nintendo Entertainment System
(1983) is an ideal teacher: it is a *complete* computer — processor, memory, bus,
I/O, interrupts — yet small enough that the whole thing fits in a handful of
readable Java classes. Every concept here (the fetch-decode-execute loop, memory
mapping, interrupts, DMA, bank switching) is exactly how the laptop you are reading
this on works. The NES just has fewer of everything, and no operating system in the
way.

> **How to read this.** Sections build on each other. Each one points at the real
> file and method that implements the idea, written as `path:line`. Open those files
> alongside this text — the goal is for you to connect a *concept* to the *code*
> that realizes it. "Try this" boxes suggest experiments.

**Contents**

1. [What a computer actually is](#1-what-a-computer-actually-is)
2. [Bits, bytes, and why the code is full of `& 0xFF`](#2-bits-bytes-and-why-the-code-is-full-of--0xff)
3. [The bus and the memory map](#3-the-bus-and-the-memory-map)
4. [The CPU, part 1: anatomy](#4-the-cpu-part-1-anatomy)
5. [The CPU, part 2: the fetch–decode–execute loop](#5-the-cpu-part-2-the-fetchdecodeexecute-loop)
6. [The CPU, part 3: addressing modes](#6-the-cpu-part-3-addressing-modes)
7. [The CPU, part 4: flags, arithmetic, and the stack](#7-the-cpu-part-4-flags-arithmetic-and-the-stack)
8. [Interrupts: how a computer reacts to the world](#8-interrupts-how-a-computer-reacts-to-the-world)
9. [The clock: keeping three chips in step](#9-the-clock-keeping-three-chips-in-step)
10. [The PPU: turning memory into a picture](#10-the-ppu-turning-memory-into-a-picture)
11. [DMA: moving memory without the CPU](#11-dma-moving-memory-without-the-cpu)
12. [The APU: making sound from counters](#12-the-apu-making-sound-from-counters)
13. [Cartridges, mappers, and bank switching](#13-cartridges-mappers-and-bank-switching)
14. [Input: the controller as a shift register](#14-input-the-controller-as-a-shift-register)
15. [The whole machine: boot to first frame](#15-the-whole-machine-boot-to-first-frame)
16. [Exercises](#16-exercises)

---

## 1. What a computer actually is

Strip away the marketing and a computer is five things wired together:

| Part | What it does | In this emulator |
|------|--------------|------------------|
| **CPU** | Executes instructions one after another | `cpu/Cpu6502.java` |
| **Memory** | Holds numbers the CPU reads and writes | `int[] ram` in `NesSystem.java` |
| **Bus** | Carries addresses and data between CPU and everything else | the `Bus` interface, implemented by `NesSystem` |
| **I/O devices** | Talk to the outside world (screen, sound, buttons) | `ppu/`, `apu/`, `controller/` |
| **Interrupts** | Let devices interrupt the CPU when something happens | `cpu.nmi()`, `cpu.irq()` |

The defining trick — the **stored-program** idea (von Neumann, 1945) — is that the
program is *just numbers in memory*, indistinguishable from data. The CPU has a
register called the **program counter** that holds "the address of the next
instruction." It reads the number there, does what that number means, advances the
counter, and repeats. Forever. That loop is the heartbeat of every computer, and you
can see it directly in `Cpu6502.clock()`.

A real NES is a circuit board. Our emulator replaces each chip with a Java object
and each wire with a method call. When the CPU object wants to read memory, instead
of asserting voltages on 16 address pins it calls `bus.cpuRead(address)`. The
*behaviour* is identical; only the medium changed. This is the deep idea behind
emulation: a computer is defined by what it *does*, not what it is made of.

---

## 2. Bits, bytes, and why the code is full of `& 0xFF`

The NES CPU is an **8-bit** processor. That phrase has a precise meaning: its
registers and the data bus are 8 bits wide, so the natural unit of data is one
**byte** — a number from 0 to 255 (`0x00` to `0xFF` in hexadecimal). Addresses are
**16 bits** wide (two bytes), so the CPU can name 2¹⁶ = 65,536 distinct locations,
`$0000`–`$FFFF`. That 64 KB is the entire world the CPU can see.

Java has no 8-bit unsigned type. Its `byte` is *signed* (−128 to 127), which is a
constant nuisance. This codebase makes a deliberate choice: **store 8-bit values in
`int`, and mask back to 8 bits at the boundaries.** That is why you see `& 0xFF`
(keep low 8 bits) and `& 0xFFFF` (keep low 16 bits) everywhere. For example, in
`Cpu6502.java`:

```java
private int read(int addr) {
    return bus.cpuRead(addr & 0xFFFF) & 0xFF;   // address wraps at 64 KB; data is a byte
}
```

A few bit-manipulation idioms recur constantly; learn to read them fluently:

- `x & 0xFF` — take the low byte (drop anything above bit 7).
- `(x >> 8) & 0xFF` — take the *high* byte of a 16-bit value.
- `(hi << 8) | lo` — combine two bytes into a 16-bit word. The 6502 is
  **little-endian**: the low byte is stored first in memory, so you'll see `lo` read
  before `hi` (e.g. `Cpu6502.ABS()`).
- `value & 0x80` — test bit 7 (the sign bit / "negative" bit).
- `(x >> n) & 1` — extract bit *n*.

**Two's complement.** How does an 8-bit register hold a *negative* number? By
convention: if bit 7 is set, the value is treated as negative. The byte `0xFF` means
−1, `0x80` means −128. To negate, flip all bits and add one. This is not a special
mode — addition works the same on signed and unsigned numbers; only *interpretation*
differs. You'll see this in branch offsets, where `REL()` sign-extends a byte:

```java
if ((offset & 0x80) != 0) {
    offset -= 0x100;   // 0x80..0xFF become -128..-1
}
```

Everything a computer does — text, pixels, sound, this very sentence — is bytes
under some agreed interpretation. Hold onto that; the PPU section will turn bytes
into colours and the APU section will turn bytes into a waveform.

---

## 3. The bus and the memory map

The CPU has exactly two ways to interact with the rest of the machine: it can
**read** a byte from a 16-bit address, or **write** a byte to one. That is the whole
interface, and it is captured literally in `cpu/Bus.java`:

```java
public interface Bus {
    int cpuRead(int addr);
    void cpuWrite(int addr, int value);
}
```

Everything else — graphics, sound, input, the game cartridge — is reachable only by
reading and writing addresses. This is **memory-mapped I/O**, and it is how nearly
all computers work. There is no separate "talk to the sound card" instruction; you
just write to the address where the sound card lives.

So who decides what each address *means*? The bus. `NesSystem` implements `Bus` and
its `cpuRead`/`cpuWrite` are one big address decoder (`NesSystem.java:74`). Here is
the NES **memory map** — the master plan of the 64 KB address space:

```
$0000-$07FF   2 KB internal RAM  ───┐
$0800-$0FFF   mirror of RAM         │ same 2 KB appears 4 times
$1000-$17FF   mirror of RAM         │ (addresses differ, storage is shared)
$1800-$1FFF   mirror of RAM      ───┘
$2000-$2007   PPU registers      ───┐
$2008-$3FFF   mirrors of those 8 ───┘ every 8 bytes
$4000-$4013   APU registers
$4014         OAM DMA trigger
$4015         APU status (R) / channel enable (W)
$4016         Controller 1 (R) / strobe both pads (W)
$4017         Controller 2 (R) / APU frame counter (W)
$4020-$FFFF   Cartridge: PRG ROM, PRG RAM, mapper registers
```

Two ideas here are worth internalizing.

**Mirroring.** The NES has only 2 KB of RAM, but it occupies an 8 KB *region*. Read
`$0000`, `$0800`, `$1000`, or `$1800` and you get the same byte, because the decoder
ignores the upper bits:

```java
if (addr <= 0x1FFF) {
    return ram[addr & 0x07FF] & 0xFF;   // 0x07FF = 2047, so only the low 11 bits matter
}
```

This isn't a bug — it's cheaper to decode fewer address lines than to fully decode
all of them, and the NES designers only wired up enough to distinguish the regions
they cared about. Mirroring is a recurring NES theme; the PPU's nametables and
palette do it too.

**Devices are addresses.** Writing to `$2006` doesn't store a byte anywhere you can
read back — it feeds the PPU's address register. Writing `$4014` doesn't store
anything; it *triggers a 256-byte DMA transfer* (more in §11). Reading `$2002` has a
**side effect**: it clears a status flag and resets a latch. An address can be a
button, a trigger, or a window into another chip's state. This is the essence of
how a CPU controls hardware it knows nothing about: the *meaning* lives in the bus
and the device, not in the CPU.

> **Try this.** In `NesSystem.cpuRead`, add a line that prints whenever the CPU
> reads `$2002`. Run a game and watch how often it polls the PPU status register —
> that's the game waiting for VBlank, the rhythm of the whole machine.

---

## 4. The CPU, part 1: anatomy

The 6502 (here, the Ricoh 2A03 variant) is a marvel of minimalism. Its entire
*architectural state* — everything that makes it "where it is" in a computation —
is six registers, declared at `Cpu6502.java:38`:

```java
private int a;       // accumulator  (8-bit) — the main arithmetic register
private int x;       // X index      (8-bit) — counter / address offset
private int y;       // Y index      (8-bit) — counter / address offset
private int sp;      // stack pointer(8-bit) — points into the stack at $0100-$01FF
private int pc;      // program counter(16-bit) — address of the next instruction
private int status;  // processor flags(8-bit) — results of the last operation
```

That's it. Forty bits of state (plus the 64 KB of external memory) fully describe a
running 6502. A modern CPU has hundreds of registers and megabytes of hidden cache,
but the *idea* is the same: a small bundle of fast internal storage that the
instruction stream shuffles data through.

- **Accumulator `a`.** Where arithmetic happens. "Add this to A", "AND this with A".
  Most data flows through it.
- **Index registers `x`, `y`.** Loop counters and address modifiers. "Load from
  address base+X" lets you walk an array.
- **Program counter `pc`.** The 16-bit address of the next instruction byte. The CPU
  bumps it as it consumes bytes; jumps and branches do it by writing `pc` directly.
- **Stack pointer `sp`.** The NES stack is a fixed 256-byte page at
  `$0100-$01FF`. `sp` is just the low byte; the hardware always prepends `$01`. See
  `push`/`pop` at `Cpu6502.java:81`:

  ```java
  private void push(int value) {
      write(0x0100 + sp, value);     // store at $01xx
      sp = (sp - 1) & 0xFF;          // stack grows DOWNWARD, wraps within the page
  }
  ```

  The stack grows from `$01FF` toward `$0100`. There is no overflow check — push too
  much and you wrap around and corrupt your own stack, exactly like the real chip.

- **Status register `status`.** Eight individual bits, each a yes/no fact about the
  last operation. Their masks are at `Cpu6502.java:26`:

  ```
  C (carry)            bit 0   carry out of bit 7 / borrow / shifted-out bit
  Z (zero)             bit 1   set when a result was zero
  I (interrupt disable)bit 2   when set, maskable IRQs are ignored
  D (decimal)          bit 3   unused on the NES (the 2A03 ignores it)
  B (break)            bit 4   distinguishes BRK from a hardware interrupt
  U (unused)           bit 5   physically always 1
  V (overflow)         bit 6   signed overflow in add/subtract
  N (negative)         bit 7   copy of bit 7 of the result
  ```

The helpers `getFlag`/`setFlag` (`Cpu6502.java:95`) just test and set bits with
masks, and `setZN(value)` is a convenience that updates the two flags almost every
instruction touches — Zero (was the result 0?) and Negative (is bit 7 set?).

---

## 5. The CPU, part 2: the fetch–decode–execute loop

Here is the heartbeat. `Cpu6502.clock()` (`Cpu6502.java:179`) is called once per CPU
cycle by the system:

```java
public void clock() {
    if (cycles == 0) {          // previous instruction finished?
        executeInstruction();   // fetch + decode + execute the next one
    }
    cycles--;                   // otherwise, just burn a cycle
    totalCycles++;
}
```

This reveals the **timing model** of this emulator, which is worth understanding
precisely. A real 6502 spreads one instruction across 2–7 clock cycles, doing a
little work each tick. This emulator instead does *all* the work of an instruction
in one go inside `executeInstruction()`, then sets `cycles` to that instruction's
*duration* and idles for the remaining ticks. From the outside — counting cycles —
it matches the real chip; internally the memory accesses happen "all at once" rather
than spread out. This is called **instruction-stepped with cycle counting**. It is
simpler and plenty accurate for games, and the README explains why the trade-off
works.

Now `executeInstruction()` (`Cpu6502.java:205`), the three classic phases:

```java
private void executeInstruction() {
    opcode = read(pc);              // FETCH: read the instruction byte at PC
    pc = (pc + 1) & 0xFFFF;         //        advance PC past it
    setFlag(FLAG_U, true);

    Instr instr = lookup[opcode];   // DECODE: look up what this opcode means
    cycles = instr.cycles();        //         its base cycle cost
    implied = false;

    int extraAddr = instr.addr().run();  // EXECUTE part 1: compute the operand address
    int extraOp = instr.op().run();      // EXECUTE part 2: perform the operation
    cycles += (extraAddr & extraOp);     // maybe one extra cycle (see below)

    setFlag(FLAG_U, true);
}
```

**Fetch.** Read one byte from `[pc]`, advance `pc`. That byte is the **opcode** — a
number naming both *what to do* and *how to find the operands*.

**Decode.** The opcode indexes a 256-entry table, `lookup[]`, built once in
`buildLookupTable()`. Each entry is a small record (`Cpu6502.java:62`):

```java
private record Instr(String name, Micro op, Micro addr, int cycles) {}
```

It bundles the operation (e.g. `ADC`), the addressing mode (e.g. `ABX`), and the
base cycle count. This **table-driven** design is how real 6502 disassemblers and
emulators are organized: the opcode *is* an index, and decoding is a single array
lookup. (Modern CPUs decode far more elaborately, but the principle — opcode →
meaning — is unchanged.)

**Execute.** Two function calls. First the **addressing mode** runs and figures out
*where* the operand is, leaving the result in `addrAbs` (or setting `implied`).
Then the **operation** runs, typically calling `fetch()` to read from `addrAbs` and
doing its work. Splitting "find the operand" from "use the operand" is what lets the
6502 have ~56 operations × ~13 addressing modes without writing 700 separate
routines — you mix and match. That factoring is the single most important structural
idea in the CPU code.

**The `& ` on the extra cycle** (`cycles += (extraAddr & extraOp)`) is a clever
encoding. Some instructions take one extra cycle *only when* an indexed address
crosses a 256-byte page boundary. The addressing mode returns 1 if a page was
crossed; the operation returns 1 if it is the *kind* of instruction that cares.
Only when *both* are 1 does the penalty apply — hence the bitwise AND. A store
instruction always reads/writes the same way regardless of page crossing, so its
operation returns 0 and never pays the penalty.

> **Try this.** Add `System.out.printf("%04X %s%n", pc-1, instr.name())` at the top
> of `executeInstruction` (after the fetch) and run a few thousand cycles. You are
> now watching a CPU think — a live instruction trace, the same thing a debugger's
> "step" button shows you.

---

## 6. The CPU, part 3: addressing modes

An **addressing mode** answers one question: *given the bytes after the opcode, what
is the address of the operand?* The 6502's expressiveness comes from having a dozen
ways to answer it. Each is a tiny method that consumes 0–2 bytes after the opcode and
leaves the effective address in `addrAbs`. Here is the full set, from
`Cpu6502.java:225`, grouped by idea:

**No operand in memory:**
- `IMP` (implied/accumulator) — the operand is a register or nothing. `INX` just
  increments X; there's no address. Sets `implied = true` so `fetch()` knows to use
  `a` instead of reading memory.
- `IMM` (immediate) — the operand byte is the next byte *in the instruction itself*.
  `LDA #$05` loads the literal 5. The "address" is simply `pc`.

**Zero page** — the first 256 bytes, `$0000-$00FF`, are special: an address there
fits in *one* byte, so these instructions are shorter and faster. Think of zero page
as the CPU's pocket of fast-access variables.
- `ZP0` — address is the single byte that follows.
- `ZPX` / `ZPY` — that byte plus X (or Y), wrapped within the page (`& 0xFF`).

**Absolute** — a full 16-bit address, low byte first:
- `ABS` — the two bytes that follow are the address.
- `ABX` / `ABY` — that address plus X (or Y). These can **cross a page** and return
  1 for the extra-cycle logic:

  ```java
  addrAbs = (base + x) & 0xFFFF;
  return ((addrAbs & 0xFF00) != (hi << 8)) ? 1 : 0;  // did the high byte change?
  ```

**Indirect** — the operand of the instruction is a *pointer* to the real address:
- `IND` — used only by `JMP`. Read a 16-bit pointer, then read the address it points
  to. This one faithfully reproduces a famous **hardware bug**: if the pointer's low
  byte is `$FF`, the 6502 fails to carry into the high byte when reading the second
  pointer byte, wrapping within the page instead. The code reproduces it on purpose
  (`Cpu6502.java:301`), because real games depended on the broken behaviour:

  ```java
  if (lo == 0x00FF) {
      addrAbs = (read(ptr & 0xFF00) << 8) | read(ptr);  // buggy wrap, as on silicon
  }
  ```

- `IZX` (indexed indirect) — `(zp + X)` points to a 2-byte address in zero page.
  Used for tables of pointers indexed by X.
- `IZY` (indirect indexed) — read a 2-byte base address from zero page, *then* add
  Y. The workhorse for "array at a pointer." Can cross a page.

**Relative** — only for branches. A *signed* one-byte offset from the current PC,
giving a reach of −128..+127 bytes. Sign-extension (`REL`, `Cpu6502.java:255`) is
what makes backward branches possible.

The reason there are so many modes: memory access patterns *are* programming. "A
constant," "a global variable," "an array element," "the thing this pointer points
at" — each is a mode. A higher-level language compiles `array[i]` down to exactly
the indexed modes above.

---

## 7. The CPU, part 4: flags, arithmetic, and the stack

Let's watch one operation end to end. `ADC` (add with carry) is the canonical
example because it exercises every status flag (`Cpu6502.java:344`):

```java
private int ADC() {
    fetch();                                       // get the operand into `fetched`
    int temp = a + fetched + (getFlag(FLAG_C) ? 1 : 0);   // 9-bit sum
    setFlag(FLAG_C, temp > 0xFF);                  // carry out of bit 7?
    setFlag(FLAG_Z, (temp & 0xFF) == 0);           // result zero?
    setFlag(FLAG_V, ((~(a ^ fetched) & (a ^ temp)) & 0x80) != 0);  // signed overflow?
    setFlag(FLAG_N, (temp & 0x80) != 0);           // result negative (bit 7)?
    a = temp & 0xFF;                               // keep 8 bits
    return 1;                                       // may take an extra cycle
}
```

Several lessons hide in these seven lines:

- **The carry flag chains arithmetic.** The 6502 adds 8 bits at a time. To add
  16-bit numbers you `ADC` the low bytes (producing a carry), then `ADC` the high
  bytes *including that carry*. The carry flag is the bridge between bytes — the same
  mechanism as carrying a digit when you add by hand. This is why it's "add *with
  carry*" and why you clear the carry (`CLC`) before the first `ADC` of a chain.

- **Overflow ≠ carry.** Carry is about *unsigned* overflow (result > 255). Overflow
  (`V`) is about *signed* overflow — adding two positives and getting a "negative"
  (e.g. 100 + 50 = 150, which as a signed byte is −106). The bit-twiddle
  `(~(a ^ fetched) & (a ^ temp)) & 0x80` says: "the inputs had the same sign, but the
  result's sign differs from them" — the exact definition of signed overflow. The
  CPU computes *both* interpretations every time and lets the program choose which to
  trust.

- **Subtraction reuses addition.** `SBC` (`Cpu6502.java:355`) just adds the *ones'
  complement* of the operand (`fetched ^ 0xFF`). Because `−n = ~n + 1` in two's
  complement and the carry supplies the `+1`, subtract is add-with-inverted-operand.
  One adder circuit does both jobs. This kind of reuse is everywhere in hardware.

- **The decimal flag is dead.** A stock 6502 has a BCD (binary-coded decimal) mode
  toggled by `D`. The NES's 2A03 has it fused off, so this emulator simply never
  consults `D`. A small reminder that "the instruction set" depends on the exact chip.

**The stack and subroutines.** When a program calls a subroutine (`JSR`), the CPU
pushes the return address onto the stack; `RTS` pops it back. Interrupts do the same
with the return address *and* the status register. Because the stack is just memory
and `sp` just an index, recursion, local data, and nested calls all fall out for
free. The entire concept of a "call stack" — which every language you've used relies
on — is these few lines of push/pop over a 256-byte page.

> **Try this.** The existing `Cpu6502Test` exercises `ADC` carry and overflow,
> `JSR`/`RTS`, and `INX` wraparound. Read `src/test/java/com/nesemu/cpu/Cpu6502Test.java`
> and trace each test by hand against the code above. Then add a test for 16-bit
> addition using two chained `ADC`s and prove the carry bridges the bytes.

---

## 8. Interrupts: how a computer reacts to the world

So far the CPU runs straight through the program. But a computer must *react* —
to a finished frame, an elapsed timer, a pressed key — without wastefully polling.
The mechanism is the **interrupt**: a hardware signal that makes the CPU drop what
it's doing, run a special handler, and return.

The 6502 has three interrupt-like events, all implemented as methods that hijack the
program counter:

**Reset** (`Cpu6502.java:125`) — not really an interrupt but the same machinery. On
power-up there is no sensible state, so the CPU reads a 16-bit address from the fixed
**reset vector** at `$FFFC/$FFFD` and jumps there. That address is baked into the
game cartridge; it is *the entry point of the program*. Registers are cleared, `sp`
is set to `$FD`, and interrupts are disabled:

```java
public void reset() {
    int lo = read(0xFFFC);
    int hi = read(0xFFFD);
    pc = (hi << 8) | lo;     // jump to wherever the cartridge says to start
    ...
    sp = 0xFD;
    status = FLAG_U | FLAG_I;
    cycles = 8;
}
```

**NMI — non-maskable interrupt** (`Cpu6502.java:161`). Raised by the PPU at the start
of vertical blank (~60 times a second). "Non-maskable" means the program *cannot*
ignore it. This is the single most important clock in NES programming: games do all
their per-frame work (update positions, copy new graphics to the PPU) inside the NMI
handler, because VBlank is the only safe window to touch PPU memory. The sequence:
push PC, push status, disable further interrupts, then jump through the **NMI vector**
at `$FFFA/$FFFB`.

**IRQ — maskable interrupt request** (`Cpu6502.java:144`). Raised by the APU's frame
counter/DMC and by the MMC3 mapper's scanline counter. "Maskable" means the program
can defer it by setting the `I` flag — and the handler does exactly that, the very
first check:

```java
public void irq() {
    if (getFlag(FLAG_I)) {
        return;              // interrupts disabled — ignore the request
    }
    push((pc >> 8) & 0xFF);
    push(pc & 0xFF);
    ...
    setFlag(FLAG_I, true);   // block further IRQs while we handle this one
    int lo = read(0xFFFE);   // jump through the IRQ vector
    int hi = read(0xFFFF);
    pc = (hi << 8) | lo;
    cycles = 7;
}
```

Notice the common shape of all three: **save where you were (on the stack), jump to a
fixed vector address.** The handler ends with `RTI`, which pops the status and PC
back, and execution resumes exactly where it was interrupted — the program never
even knows it happened. Those three vectors live at the very top of the address
space:

```
$FFFA-$FFFB   NMI vector
$FFFC-$FFFD   Reset vector
$FFFE-$FFFF   IRQ/BRK vector
```

Every modern OS context switch, every device driver, every "your code stops and the
kernel runs" moment is this same idea, scaled up: a signal, a save, a jump to a
fixed handler, a return. The NES shows it in its irreducible form.

---

## 9. The clock: keeping three chips in step

A real NES contains three clocked chips running concurrently: the CPU, the PPU, and
the APU. In hardware they tick simultaneously off a shared master oscillator. In
software we have one thread, so we **interleave** them — and the interleaving must
respect the real frequency ratios or games break.

The key fact: **the PPU runs exactly three times as fast as the CPU.** The master
clock in `NesSystem.clock()` (`NesSystem.java:131`) encodes that 3:1 ratio:

```java
public void clock() {
    ppu.clock();                              // PPU ticks every master cycle

    if (systemClockCounter % 3 == 0) {        // CPU + APU tick every THIRD cycle
        apu.clock();
        if (dmaTransfer) {
            stepDma();                        // ...unless DMA has stolen the CPU's turn
        } else {
            cpu.clock();
        }
        if (apu.isIrqAsserted())  cpu.irq();  // deliver pending interrupts
        if (cartridge != null && cartridge.isMapperIrqAsserted()) cpu.irq();
    }

    if (ppu.isNmiRequested()) {               // PPU may have entered VBlank this tick
        ppu.clearNmiRequest();
        cpu.nmi();
    }

    systemClockCounter++;
}
```

Read it as "one PPU dot per call; one CPU cycle every three dots." This single method
is the synchronization backbone of the whole machine — it is *why* the sprite-zero
trick (§10) works, because the CPU and PPU stay in lock-step at exactly the hardware
ratio. Get the ratio wrong and the split-scroll status bar in *Super Mario Bros.*
tears apart.

How does the outside world drive this clock? In coarse units. The UI never calls
`clock()` directly; it calls `stepFrame()` (`NesSystem.java:185`):

```java
public void stepFrame() {
    do {
        clock();
    } while (!ppu.isFrameComplete());   // run until the PPU finishes a whole frame
    ppu.clearFrameComplete();
}
```

So the **frame** is the unit of work the emulator hands the rest of the program. The
background emulation thread in `EmulatorFrame` calls `stepFrame()`, draws the
resulting image, and paces itself to ≈60.0988 Hz (NTSC). Audio back-pressure helps:
when the sound buffer is full the write blocks just long enough to keep emulation
locked to real time, so audio doubles as a clock (see the README's "How a frame is
produced").

This is a general pattern in systems with concurrent hardware: pick the fastest
clock as the base tick, derive the slower ones by counting, and choose a coarse unit
(here, the frame) as the interface to everything above.

---

## 10. The PPU: turning memory into a picture

The Picture Processing Unit is a second processor dedicated to video. It is, frankly,
the hardest part of the NES to understand, so we'll build it up in layers. The whole
thing lives in `ppu/Ppu.java`.

### 10.1 The output and the timing grid

The PPU produces a **256×240** image. But it doesn't compute the image and hand it
over; it generates it **one pixel ("dot") at a time**, left to right, top to bottom,
exactly as an old CRT television scans its electron beam. Its sense of time is a
grid (`Ppu.java:80`):

```
            cycle (dot): 0 ........................ 340   (341 per scanline)
 scanline
   -1   pre-render  ┐
    0   visible     │  240 visible lines produce the image
   ...  visible     │
  239   visible     ┘
  240   post-render    (idle)
  241   VBlank start → raise NMI here
  ...   VBlank         (the CPU's safe window to update the PPU)
  260   VBlank end
```

That's 341 × 262 = 89,342 dots per frame. The pre-render line `-1` and the off-screen
dots (256–340, and lines 240–260) are not wasted — they're when the PPU does the
fetching and bookkeeping for *upcoming* visible pixels, and when it signals VBlank.
`Ppu.clock()` (`Ppu.java:335`) advances this grid one dot per call and contains the
entire rendering pipeline.

The crucial event is **VBlank** (`Ppu.java:413`):

```java
if (scanline == 241 && cycle == 1) {
    statusReg |= STATUS_VBLANK;            // tell the CPU "frame done, safe to write"
    if ((control & CTRL_NMI) != 0) {
        nmiRequest = true;                 // ...and interrupt it to do so
    }
}
```

This is the handshake between the two processors: the PPU finishes drawing, sets the
VBlank flag, and (if enabled) fires the NMI that wakes the game's per-frame logic.

### 10.2 How a tile becomes pixels

The NES screen is built from 8×8 **tiles**, not individual pixels — there isn't
nearly enough memory for a full framebuffer in ROM. Two kinds of memory describe the
picture:

- **Pattern tables** (in cartridge CHR memory, PPU `$0000-$1FFF`): the *shapes*. Each
  tile is 16 bytes — 8 bytes for "bit-plane 0" and 8 for "bit-plane 1." For each of
  the 64 pixels, one bit from each plane combine into a 2-bit number 0–3: the pixel's
  **colour index within its palette.** Two bits → four possible colours per tile.

- **Nametables** (PPU on-board VRAM, `$2000-$2FFF`): the *layout*. A nametable is a
  32×30 grid of tile numbers — "put tile #65 here, tile #66 next to it." 960 bytes of
  tile indices plus 64 bytes of **attributes** that assign each 2×2 tile block to one
  of four background palettes.

So a background pixel is computed by: *look up which tile goes here (nametable) → read
that tile's two bit-planes (pattern table) → combine to a 2-bit colour index → look up
the actual colour through the attribute-selected palette.* The PPU does this lookup
chain continuously as the beam sweeps. You can see the fetches in the big `switch`
inside `clock()` (`Ppu.java:356`), spread across 8-dot cycles: tile id, then attribute,
then pattern low byte, then pattern high byte — the four reads needed per tile.

The final colour comes from the **palette** (`$3F00-$3F1F`, 32 bytes) which holds
indices into a fixed 64-entry master palette of NES colours. `buildNesPalette()`
(`Ppu.java:653`) is that master list as ARGB; `colourFromPalette()` does the final
two-step lookup. Note the palette mirroring (`paletteIndex`, `Ppu.java:234`): entries
`$3F10/$14/$18/$1C` mirror `$3F00/$04/$08/$0C` — the same "decode fewer bits" frugality
as main RAM.

### 10.3 Scrolling: the "loopy" registers

Games scroll smoothly even though the world is built from fixed tiles. The mechanism
is two 15-bit internal registers, nicknamed **v** and **t** after the reverse-engineer
who decoded them (`Ppu.java:55`):

```java
private int vramAddr;  // "v" — the address the PPU is currently rendering from
private int tramAddr;  // "t" — a staging copy, loaded from CPU writes to $2005/$2006
private int fineX;     // sub-tile horizontal scroll (0-7)
private boolean addressLatch; // "w" — tracks first vs. second write
```

The clever part is that **v is not just an address — its bits are packed coordinates**:

```
  yyy NN YYYYY XXXXX
  ||| || ||||| +++++-- coarse X  (which tile column, 0-31)
  ||| || +++++-------- coarse Y  (which tile row, 0-29)
  ||| ++-------------- nametable select (which of 4 screens)
  +++----------------- fine Y    (which pixel row within the tile, 0-7)
```

Incrementing the rendering position is therefore done by incrementing *fields* of v:
`incrementScrollX()` bumps coarse X and flips the horizontal nametable bit on wrap;
`incrementScrollY()` handles fine Y, coarse Y, and the awkward 30-row wrap
(`Ppu.java:266`). When a game writes a scroll value to `$2005`, it's really stuffing
coordinate bits into `t`; at specific dots the PPU copies the relevant bits from `t`
into `v` (`transferAddressX/Y`). This dual-register dance is what makes a single
`$2005` write produce glassy-smooth scrolling. It is the most over-engineered-looking
part of the chip and the most elegant once it clicks.

### 10.4 Sprites and the sprite-zero trick

Backgrounds are one layer; **sprites** (movable objects — Mario, enemies, bullets)
are another. Their positions live in **OAM** (Object Attribute Memory), 256 bytes
holding 64 sprites × 4 bytes (Y, tile, attributes, X). Because the NES can only draw
**8 sprites per scanline**, the PPU does **sprite evaluation** each line
(`evaluateSprites`, `Ppu.java:519`): it scans all 64 sprites, finds the (up to 8) that
overlap the *next* line, and copies them to a small secondary buffer. The 9th sets an
overflow flag. This is why old games flicker when too many objects line up — the
hardware literally can't draw the rest.

`composePixel()` (`Ppu.java:437`) is the per-dot **priority multiplexer**: for each
pixel it has a background colour and possibly a sprite colour, and rules decide which
wins (sprite priority bit, transparency). Read the cascade of `if (bgPixel == 0 ...)`
to see the exact precedence.

Tucked inside is the famous **sprite-zero hit** (`Ppu.java:498`). When the first
opaque pixel of sprite 0 overlaps an opaque background pixel, the PPU sets a status
bit. Why does anyone care? Because it tells the CPU *exactly where the beam is* mid-
frame. *Super Mario Bros.* places sprite 0 at the bottom of the status bar; the game
busy-waits for the hit, and the instant it fires it changes the scroll registers —
freezing the status bar while the level below scrolls. A split screen, achieved with
a single bit of timing feedback and no extra hardware. This trick is the reason the
emulator bothers to be dot-accurate in the PPU at all (§9).

> **Try this.** In `composePixel`, force `pixel = 1` (always draw colour 1) and run a
> game — you'll see the tile *shapes* with palette flattened, which makes the
> tile/pattern structure visible. Then restore it and instead log when
> `STATUS_ZERO_HIT` gets set; correlate it with the scanline to *see* the split point.

---

## 11. DMA: moving memory without the CPU

Updating all 64 sprites means copying 256 bytes into OAM every frame. Doing that with
CPU load/store instructions would cost hundreds of cycles of precious VBlank time. So
the NES provides **DMA** (Direct Memory Access): a write to `$4014` makes dedicated
hardware copy a whole 256-byte page from CPU RAM into OAM, while the CPU is paused.

The trigger is just a bus write (`NesSystem.java:104`):

```java
} else if (addr == 0x4014) {
    dmaPage = value;       // high byte of the source address ($xx00)
    dmaAddr = 0x00;
    dmaTransfer = true;    // the master clock now runs DMA instead of the CPU
    dmaDummy = true;
}
```

After that, look back at `NesSystem.clock()` (§9): while `dmaTransfer` is true the
CPU's turn is spent on `stepDma()` instead of `cpu.clock()`. The CPU is *suspended* —
it makes no progress — exactly as on hardware. `stepDma()` (`NesSystem.java:164`)
copies one byte per two cycles, alternating a read cycle and a write cycle, after an
initial alignment wait:

```java
private void stepDma() {
    if (dmaDummy) {                        // wait to land on an even cycle
        if (systemClockCounter % 2 == 1) dmaDummy = false;
        return;
    }
    if (systemClockCounter % 2 == 0) {
        dmaData = cpuRead((dmaPage << 8) | dmaAddr);   // read cycle
    } else {
        ppu.oamDmaWrite(dmaData);                       // write cycle
        dmaAddr = (dmaAddr + 1) & 0xFF;
        if (dmaAddr == 0x00) {                          // wrapped past $FF → 256 done
            dmaTransfer = false;
            dmaDummy = true;
        }
    }
}
```

Two general principles live here. First, **DMA is a co-processor for memory moves** —
the same reason your real computer has DMA controllers so the disk and network can
fill RAM without burning CPU time. Second, **it costs the CPU stall time** (~513
cycles), so it isn't free; it's a trade of CPU cycles for not having to execute a copy
loop. Games budget for it inside VBlank.

---

## 12. The APU: making sound from counters

The Audio Processing Unit (`apu/Apu.java`) generates sound with **five channels**:
two pulse (square) waves, a triangle wave, a noise generator, and a DMC sample player.
There is no concept of "audio data" being computed by the CPU in real time — instead
each channel is a little state machine of **counters**, and the CPU merely sets their
parameters by writing registers `$4000-$4013`.

The core idea: a sound is a number that changes over time at a controlled rate. Take
the pulse channel. It steps through an 8-entry **duty cycle** pattern (`DUTY_TABLE`,
`Apu.java:31`):

```
12.5%  {0,1,0,0,0,0,0,0}
 25%   {0,1,1,0,0,0,0,0}
 50%   {0,1,1,1,1,0,0,0}
```

A **timer** counts down at the CPU clock; each time it hits zero the channel advances
to the next duty step, emitting a 0 or 1. Step through `{0,1,1,1,1,0,0,0}` repeatedly
and you get a square wave whose *pitch* is set by how fast the timer counts (the timer
period, written to `$4002/$4003`) and whose *tone* is set by which duty pattern. That's
it — a square wave is a counter cycling through a bit pattern.

The other channels are variations on the theme:
- **Triangle** steps a 32-entry ramp 15→0→15 (`TRIANGLE_SEQ`), producing a smooth
  triangle wave used for bass lines.
- **Noise** uses a pseudo-random shift register clocked at one of 16 rates
  (`NOISE_PERIOD`) for percussion and effects.
- **DMC** plays back delta-encoded samples fetched *from CPU memory* — which is why
  `NesSystem` hands the APU a `DmcReader` that reads the bus (`NesSystem.java:53`).

Tying them together is the **frame sequencer** (`clockFrameSequencer`, `Apu.java:193`),
a master timer that fires 4 or 5 times per frame to clock each channel's *envelope*
(volume decay), *sweep* (pitch slide), and *length counter* (note duration). It is a
clock-divider tree: one fast clock fanned out to slower musical events. The 5-step
mode can also raise an IRQ — one of the maskable interrupts you met in §8.

Finally the five channel outputs are combined by a **non-linear mixer** (the NES
doesn't simply add them; louder channels contribute proportionally less), passed
through a DC-blocking filter, and **resampled** from the CPU's ~1.79 MHz tick down to
44,100 samples/second for your sound card (`CYCLES_PER_SAMPLE`, `Apu.java:20`).
Resampling — converting between two clock rates — is the same problem the §9 master
clock solves, here at audio resolution.

The takeaway: complex sound emerges from simple counters clocked at controlled rates
and combined. No waveform is ever "stored"; it is *generated* on the fly, which is
why the entire NES soundtrack fits in a few register writes per frame.

---

## 13. Cartridges, mappers, and bank switching

Here's a puzzle. The CPU can only address 64 KB, of which barely 32 KB is available
for program ROM (`$8000-$FFFF`). Yet *Super Mario Bros. 3* is 384 KB. How does a
program bigger than the address space run?

The answer is **bank switching**, and it is performed by a chip *on the cartridge*
called a **mapper**. This is one of the most important architectural ideas in the
whole system: **the cartridge is not passive storage — it is active hardware that
extends the computer.** Inserting a game literally adds circuitry.

### 13.1 Loading: the iNES format

A `.nes` file starts with a 16-byte header parsed by `INesHeader.java`. It records how
much PRG (program) and CHR (graphics) ROM follow, the mirroring, and — split across
two nibbles for historical reasons — the **mapper number** (`INesHeader.java:72`):

```java
int mapperLow  = (flags6 >> 4) & 0x0F;
int mapperHigh = (flags7 >> 4) & 0x0F;
int mapperId   = (mapperHigh << 4) | mapperLow;   // e.g. 0 = NROM, 4 = MMC3
```

`Cartridge.fromBytes()` (`Cartridge.java:33`) slices out the PRG and CHR data and
constructs the matching mapper object (`createMapper`, `Cartridge.java:69`). From then
on, *every* CPU access to `$4020-$FFFF` and *every* PPU access to `$0000-$1FFF` is
routed through the mapper — see `NesSystem.cpuRead` deferring to `cartridge.cpuRead`,
and `Ppu.ppuRead` deferring to `cartridge.ppuRead`. The mapper sits between the
processors and the ROM chips and gets to *lie* about which bytes are where.

### 13.2 Mapper 0 (NROM): no switching

The simplest cartridge, `Mapper0.java`, has no banking at all. It just maps the ROM
straight through, with one wrinkle: a 16 KB game is *mirrored* into both halves of the
`$8000-$FFFF` window (`Mapper0.java:29`):

```java
this.prgMask = (prgBanks > 1) ? 0x7FFF : 0x3FFF;   // 32 KB spans fully; 16 KB wraps
```

This is the baseline: a fixed map, the address decoded directly into the ROM array.
*Super Mario Bros.*, *Donkey Kong*, and many launch titles are NROM.

### 13.3 Mapper 4 (MMC3): banking and a timed interrupt

`Mapper4.java` (the MMC3, which we added to fix the "unsupported mapper" error) is how
the big games fit. It divides the program window into **four 8 KB slots** and lets the
game choose which physical 8 KB ROM bank appears in each — except two slots are
permanently nailed to the last two banks so the reset/interrupt vectors never move.
The mapping is recomputed whenever the game writes a bank register (`updateBanks`,
`Mapper4.java`):

```java
// PRG mode 0: $8000=R6, $A000=R7, $C000=second-to-last, $E000=last (fixed)
prgOffset[0] = r6;
prgOffset[1] = r7;
prgOffset[2] = secondLast * 0x2000;
prgOffset[3] = last * 0x2000;
```

Reads then index through that indirection (`cpuRead`):

```java
int window = (addr - 0x8000) >> 13;    // which 8 KB slot, 0-3
return prgRom[prgOffset[window] + (addr & 0x1FFF)] & 0xFF;
```

So `$8000` might be byte 0 of the ROM one moment and byte 196,608 the next, depending
on what the game last wrote to the bank-select register. The program "pages in"
whichever code or graphics it needs, like turning to a different chapter of a book
through a fixed window. CHR graphics are banked the same way at even finer 1 KB/2 KB
granularity, which is how MMC3 games animate large tilesets.

MMC3 also adds a **scanline counter IRQ** — the third interrupt source from §8. The
game programs a number into the counter; the counter ticks once per rendered scanline;
when it reaches zero it raises an IRQ. This gives the CPU a precise "we've reached
scanline N" signal *without* the sprite-zero busy-wait, enabling mid-screen splits
anywhere on the screen (status bars, parallax). In this emulator the tick is driven
from the PPU (`Ppu.clock()` calls `cartridge.clockScanlineCounter()` at dot 260 of
each rendered line), and the resulting IRQ is delivered through the same
`NesSystem.clock()` path as every other interrupt:

```java
@Override
public void clockScanlineCounter() {
    if (irqCounter == 0 || irqReload) { irqCounter = irqLatch; irqReload = false; }
    else                              { irqCounter--; }
    if (irqCounter == 0 && irqEnabled) irqAsserted = true;
}
```

The broader lesson: the address space is a *namespace*, not a fixed wiring. A layer of
indirection (the mapper) between the processor and physical memory lets a small
address space access a large memory — the exact principle behind **virtual memory** in
the computer you're using now, where the MMU maps a process's addresses to scattered
physical pages.

---

## 14. Input: the controller as a shift register

The humble controller (`controller/Controller.java`) teaches a real hardware protocol
in 80 lines. The NES pad has 8 buttons but is read through a *single bit* at `$4016`,
one button per read. How? It's a **shift register** — 8 latched bits that march out
one at a time.

The protocol (documented at the top of `Controller.java`):

1. The CPU writes `$4016` with bit 0 = 1 ("strobe high"). While high, the controller
   continuously copies the live button state into its shift register
   (`Controller.java:52`).
2. The CPU writes bit 0 = 0 ("strobe low"), **latching** a snapshot.
3. Each *read* of `$4016` returns the next bit and shifts (`Controller.java:66`):

   ```java
   int bit = shiftRegister & 0x01;
   shiftRegister = (shiftRegister >> 1) | 0x80;  // shift right; feed 1s in at the top
   return bit;
   ```

The buttons come out in the fixed order A, B, Select, Start, Up, Down, Left, Right.
After 8 reads the register is empty and keeps returning 1 (the `| 0x80` ensures that).
Serializing 8 parallel signals onto one wire to save pins is a classic hardware
trade-off — the same idea as SPI, shift-register LED drivers, and every "bit-banged"
serial protocol.

One subtlety worth noticing: `buttonState` is `volatile` and written by the Swing UI
thread while the emulation thread reads it. That `volatile` is the thread-safety
contract between the two threads — a real concern the moment a program has more than
one thread touching shared state.

> **Try this.** Add a `Turbo A` that auto-fires by toggling `BUTTON_A` in
> `buttonState` every frame from the emulation loop. You'll appreciate why the latch
> step exists — set the bit only while strobe is high and watch the timing.

---

## 15. The whole machine: boot to first frame

Let's assemble everything into the life of a single frame, from a cold boot.

1. **Launch.** `Main.main` (`Main.java:22`) starts Swing and creates an
   `EmulatorFrame`. If a ROM path was given, it loads it.

2. **Insert cartridge.** `Cartridge.fromFile` parses the iNES header, slices PRG/CHR,
   and builds the mapper (§13). `NesSystem.insertCartridge` wires it into both the CPU
   bus and the PPU.

3. **Reset.** `cpu.reset()` reads the reset vector at `$FFFC/$FFFD` (§8) and jumps to
   the game's entry point. The PPU and APU reset to known states.

4. **Run a frame.** The emulation thread calls `nes.stepFrame()`, which spins
   `nes.clock()` until the PPU reports a full frame (§9). Inside that spin, ~89,342
   times:
   - the PPU draws one dot, fetching tile/sprite data through the mapper and composing
     a pixel (§10);
   - every third dot, the CPU executes a slice of an instruction (§5) or is stalled by
     DMA (§11), and the APU advances its counters (§12);
   - at scanline 241 the PPU raises **NMI**; `NesSystem` delivers it; the game's
     per-frame handler runs, reads the controller (§14), updates game state, and queues
     graphics — often firing an **OAM DMA** to refresh sprites;
   - MMC3 games may take a **scanline IRQ** mid-frame to split the screen (§13).

5. **Present.** `stepFrame` returns; `EmulatorFrame` copies the PPU's 256×240
   framebuffer to a `ScreenPanel` (scaled, aspect-correct), drains the APU's audio
   samples to the sound card, and sleeps to hold ≈60 fps.

6. **Repeat**, sixty times a second, forever.

Every concept in this tutorial appears in that loop: stored-program execution, the
memory-mapped bus, interrupts as the synchronization fabric, two processors locked at
a fixed clock ratio, DMA for bulk moves, mappers extending a small address space, and
serial I/O for input. That is a complete computer. The machine on your desk does all
of the same things — it just has more cores, a memory hierarchy, an OS scheduling many
programs, and gigahertz where the NES has megahertz. The *ideas* are the ones you just
read in a few thousand lines of Java.

---

## 16. Exercises

Roughly in order of difficulty. Each one forces you to understand a section deeply.

1. **Instruction trace.** Print every executed opcode with its PC and registers
   (§5). Compare against a known-good 6502 trace (e.g. `nestest`) to verify your
   understanding of a few instructions by hand.

2. **Memory watch.** Add a "watchpoint" to `NesSystem.cpuWrite` that logs writes to a
   chosen address. Use it to find where a game stores, say, the player's score or lives
   counter in RAM (§3).

3. **Disassembler.** Using `lookup[]` and the addressing-mode byte counts, write a
   function that prints the instruction at any address as text (`LDA $0300,X`). This
   makes the decode step (§5) concrete.

4. **New CPU test.** Add a JUnit test for 16-bit addition via chained `ADC`, proving
   the carry flag bridges bytes (§7). Then test the `JMP (indirect)` page-boundary bug
   (§6) and confirm the emulator reproduces it.

5. **Palette viewer.** Render the current contents of the pattern tables and palettes
   to a window (§10.2). You'll see the game's entire tileset — and understand exactly
   what CHR memory holds.

6. **Visualize the sprite-zero hit.** Log the scanline at which `STATUS_ZERO_HIT` is
   set each frame and overlay a line on the output. Watch it track the status-bar split
   in *Super Mario Bros.* (§10.4).

7. **Another mapper.** Implement **Mapper 2 (UxROM)** — it has a single switchable
   16 KB PRG bank and a fixed one, simpler than MMC3. Follow the `Mapper`/`Mapper4`
   pattern (§13). This proves you understand bank switching.

8. **Cycle-accurate CPU (hard).** Convert `Cpu6502` from instruction-stepped to truly
   cycle-stepped, performing one memory access per `clock()` call (§5). This is the
   deepest change and teaches you exactly how the timing model is an *approximation*.

---

*Where to go next.* The definitive reference for every register and timing detail is
the [NESdev Wiki](https://www.nesdev.org/wiki/). Read this codebase with it open; this
tutorial gives you the mental model, and the wiki gives you the exhaustive specifics.
The single best way to learn, though, is to break things: change a constant, see what
shatters, and understand *why*. A computer is the most inspectable machine ever built —
and this one fits in your head.

Part I gave you the *map*. Part II is the *construction manual*: the order to build
in, and — crucially — how to *prove each piece correct* before you build the next on
top of it.

---

# Part II — Building It Yourself, From an Empty Folder

Part I explained how the finished machine works. This part answers a different
question: **if you deleted every `.java` file, in what order would you rewrite them,
and how would you know each step was right?** That second clause is the whole game.
An emulator is mostly a debugging exercise: thousands of tiny numeric behaviours that
are either bit-exact or subtly, maddeningly wrong. The difference between a project
that boots *Super Mario Bros.* and one that shows garbage is almost never "I forgot a
feature" — it's "instruction `$71` adds the carry one cycle too late." You cannot
eyeball that. You need **golden references**.

## The two rules of building an emulator

**Rule 1: Build in dependency order, never feature order.** Each layer must stand on a
*verified* layer beneath it. A PPU built on an unverified CPU is undebuggable, because
when the screen is wrong you can't tell whose fault it is. The order is forced by what
depends on what:

```
   ROM loader ─► CPU ─► Bus/system ─► PPU background ─► sprites ─► input ─► mappers ─► APU
   (M0)         (M1)   (M2)          (M3)               (M4)       (M5)     (M6)        (M7)
```

**Rule 2: Each milestone ends at a test you cannot argue with.** Not "it looks right"
— a *pass/fail* check against a reference someone else's correct emulator agrees with.
The NES homebrew community has built an extraordinary library of **test ROMs** exactly
for this. They are your unit tests. The milestones below are organized around them.

> **Get the test ROMs.** `nestest.nes` (+ `nestest.log`) for the CPU; Blargg's
> `instr_test-v5`, `ppu_vbl_nmi`, `sprite_hit_tests`, `apu_test`; and the
> `mmc3_test` suite. All are freely distributed on the NESdev Wiki and its forums.
> They are not game ROMs — they are diagnostic programs that print "PASSED" / a
> failure code, and they are legal to download. Keep them in a `testroms/` folder.

---

## Milestone 0 — Scaffolding and the ROM loader

**Goal:** read a `.nes` file into memory and parse its header. No emulation yet.

This is deliberately tiny, to get the project compiling and to force you to understand
the input format before anything consumes it. Build:

- `INesHeader.parse(byte[])` — validate the `NES\x1A` magic, extract PRG size (×16 KB),
  CHR size (×8 KB), the split mapper number, and mirroring. (`cartridge/INesHeader.java`
  is your reference; re-derive it, don't copy.)
- `Cartridge.fromBytes(byte[])` — skip the 16-byte header (and 512-byte trainer if
  present), slice out PRG and CHR.

**Verification:** print the header of a known ROM and confirm the numbers. For
`nestest.nes` you should see 1 PRG bank (16 KB), 1 CHR bank, mapper 0. If your PRG
size or mapper nibble is wrong, *every* later milestone fails mysteriously — so get
this exactly right now. This is also where you learn the discipline of masking
(`& 0xFF`) on every byte you pull from the file, because Java's signed `byte` will bite
you the moment a value exceeds 127.

**Common bugs:** combining the mapper nibbles backwards (`high << 4 | low`, not the
reverse); forgetting the trainer offset; treating CHR-size 0 as "no graphics" instead
of "8 KB of CHR *RAM*."

---

## Milestone 1 — The CPU, proven by nestest

**This is the milestone that makes or breaks the project.** A correct CPU is the
bedrock; spend real time here. The good news: the CPU is the *most* testable component
in the whole machine, because `nestest` exercises every instruction and gives you a
cycle-exact golden log to diff against.

### 1a. Write the CPU headless

Build `Cpu6502` with no PPU, no graphics — just the six registers (§4), the
fetch–decode–execute loop (§5), the addressing modes (§6), and the operations. For
memory, give it a temporary flat `int[65536]` array as its `Bus` so you can run before
the real bus exists. Implement the full opcode table — all 151 official opcodes plus
the handful of unofficial NOPs/SBC that `nestest` touches (see Appendix A for the
structure and `Cpu6502.buildLookupTable()` for the exact map).

### 1b. The nestest harness

`nestest` has a special **automated mode**: instead of starting at the reset vector,
you force the CPU into a known initial state and let it run a fixed script that walks
every opcode. The exact starting state (this is not negotiable — the golden log
assumes it):

```
PC = $C000     A = $00   X = $00   Y = $00
P  = $24       SP = $FD          cycle counter = 7
```

The repo's CPU exposes exactly the hooks you need for this: `setPc(0xC000)`,
`getA/X/Y/Sp/Status`, `getTotalCycles`, and `getOpcodeName`. Before executing each
instruction, print a line in the **nestest log format**:

```
C000  4C F5 C5  JMP $C5F5   A:00 X:00 Y:00 P:24 SP:FD CYC:7
^PC   ^opcode    ^disasm      ^registers              ^cumulative CPU cycles
```

Then diff your output against `nestest.log` line by line. **The first line that
differs is the first thing you got wrong** — and because the log shows registers
*before* each instruction, the bug is in the instruction on the *previous* line.

### 1c. Read the result codes

After the run, `nestest` writes a result byte to `$0002` (official opcodes) and `$0003`
(unofficial). `$00` means all passed; anything else is a documented error code telling
you which instruction group failed. So you have *two* nets: the cycle-exact trace diff
(catches everything, including timing) and the result byte (catches behaviour).

**Verification bar:** your trace matches `nestest.log` for all ~8,991 lines, and
`$0002 == $00`. When this passes, you have a genuinely correct 6502. Do not proceed
until it does. Every hour spent here saves ten later.

**Common bugs (in rough order of frequency):**
- `ADC`/`SBC` overflow flag (`V`) wrong — see the formula in §7.
- Page-cross extra cycles applied to the wrong instructions (the `addr & op` trick, §5).
- The `JMP (indirect)` page-boundary bug not reproduced (§6) — `nestest` checks it.
- Stack pushes/pops off by one, or not wrapping within `$0100-$01FF`.
- Status register bits 4/5 (`B`/`U`) pushed wrong by `PHP`/`BRK`/interrupts.
- Forgetting that `read`/`write` must mask address to 16 bits and data to 8.

---

## Milestone 2 — The bus and a minimal system

**Goal:** replace the CPU's flat-array memory with the real address decoder, so reads
and writes route to RAM, (stub) PPU registers, and the cartridge.

Now build `NesSystem implements Bus` (§3): the 2 KB `ram[]` with mirroring
(`addr & 0x07FF`), the `$4020+` routing to the cartridge mapper, and *stub* handlers
for `$2000-$3FFF` and `$4000-$401F` that just store/return bytes for now. Wire
`cpu.reset()` to read the reset vector. Implement `Mapper0` (NROM, §13.2) — the
simplest possible mapper — so a real ROM's PRG appears at `$8000-$FFFF`.

**Verification:** `nestest` again, but this time in **normal mode** — let the CPU
reset through `$FFFC` and run the ROM as intended, with your real bus underneath. It
should still reach `$0002 == $00`. You've now proven the bus doesn't corrupt the CPU
you already trust. Also run a couple of Blargg `instr_test-v5` ROMs; they report
PASSED by writing text to a known RAM location (and to `$6000`), which you can dump.

**Common bugs:** RAM mirror mask wrong (`0x07FF` vs `0x1FFF`); cartridge not consulted
for `$8000+`; 16 KB PRG not mirrored into both halves of the window.

---

## Milestone 3 — The PPU: a static background

**Goal:** put a correct, non-scrolling background picture on screen. This is the first
*visible* milestone and the most conceptually dense (re-read §10).

Build incrementally — do **not** try to write the whole dot-accurate pipeline at once:

1. **Registers and timing skeleton.** Implement the `$2000-$2007` registers (control,
   mask, status, OAM addr, the `$2005`/`$2006` scroll/address latch, buffered `$2007`
   reads). Implement the dot/scanline counter (341×262) and the VBlank flag + NMI at
   scanline 241 (§10.1). At this point you render nothing, but the CPU can talk to the
   PPU and gets its NMI.
2. **VRAM and mirroring.** Add the two nametables, the 32-byte palette (with its
   `$3F10/$14/$18/$1C` mirroring), and the nametable mirroring modes (§10.2).
3. **Background fetch + render.** Implement the per-dot tile fetch (nametable →
   attribute → pattern low → pattern high) and the shift registers, then compose one
   background pixel per visible dot into a `256×240` framebuffer.

**Verification:** Blargg's `ppu_vbl_nmi` test suite checks your VBlank-flag timing and
NMI behaviour precisely — this is where the subtle "when exactly does the VBlank flag
set/clear" quirks (Appendix B) get caught. For the *picture*, the classic smoke test
is that a game's title screen renders correctly: boot *Donkey Kong* or *Super Mario
Bros.* and you should see the title art (it won't be interactive yet, and scrolling
may be wrong). A **palette/pattern viewer** (Part I, Exercise 5) is invaluable here —
render CHR directly so you can see whether your tile decoding is right *independent* of
nametables.

**Common bugs:** the loopy `v`/`t` bit layout wrong (garbled or offset background);
attribute-byte quadrant selection wrong (right shapes, wrong colours in 16×16 blocks);
fine-X scroll ignored; buffered `$2007` read not implemented (games that read VRAM see
stale/wrong data).

---

## Milestone 4 — Sprites and the sprite-zero hit

**Goal:** draw the 64 movable objects, and implement the sprite-zero hit so split-screen
games work.

Add OAM (256 bytes), per-scanline sprite evaluation (find ≤8 sprites on the next line,
set overflow on the 9th), sprite pattern fetching with horizontal/vertical flip, the
background-vs-sprite priority multiplexer, and the sprite-zero hit flag (§10.4). Add
the `$4014` **OAM DMA** path in the bus/system (§11) — almost every game uses it to
load sprites each frame.

**Verification:** Blargg's `sprite_hit_tests` and `sprite_overflow_tests` are exactly
targeted here. Visually: in *Super Mario Bros.*, the status bar at the top must stay
*fixed* while the level scrolls — that split is driven entirely by the sprite-zero hit
firing at the right scanline. If the whole screen scrolls together, your hit timing is
off. 8×16 sprite mode (used by *SMB3* and others) is a common follow-up bug.

**Common bugs:** off-by-one in the sprite Y comparison (sprites one line too high/low);
forgetting sprites are delayed by one scanline (evaluate line N during line N−1);
sprite-zero hit reported in the left 8 pixels when it should be masked; OAM DMA not
stalling the CPU.

---

## Milestone 5 — Input

**Goal:** read the controller. Small but essential, and it makes the emulator
*interactive* for the first time.

Implement the `Controller` shift-register protocol (§14): strobe on `$4016` write,
serialize 8 buttons LSB-first on successive `$4016`/`$4017` reads, return 1 after the
8th read. Wire keyboard events to `setButton`.

**Verification:** boot a game and *play* it. Press Start on a title screen and reach
gameplay; confirm every button maps correctly (the read *order* — A, B, Select, Start,
Up, Down, Left, Right — is what catches mistakes). This is the first milestone you
verify by hand rather than by test ROM, and it's a satisfying one.

**Common bugs:** wrong button order; not reloading the shift register while strobe is
high; reading `$4016`/`$4017` returning the whole state instead of one bit; the
UI-thread/emulation-thread race (make button state `volatile`, §14).

---

## Milestone 6 — Mappers (bank switching)

**Goal:** run games bigger than the address space. Start with a simple mapper, then do
MMC3.

Generalize your `Mapper` interface (it already exists from M2's `Mapper0`). Implement
**Mapper 2 (UxROM)** first — one switchable 16 KB PRG bank + one fixed, no CHR banking,
no IRQ. It's the gentlest introduction to bank switching and runs *Mega Man*,
*Castlevania*, *Contra*. Then implement **Mapper 4 (MMC3)** (§13.3): 8 KB PRG windows
with two fixed banks, 1 KB/2 KB CHR banking with A12 inversion, the mirroring register,
and the scanline IRQ counter. Wire the IRQ through the PPU (`clockScanlineCounter` at
the right dot) and into the CPU's IRQ line (§8, §9).

**Verification:** the `mmc3_test` suite checks the IRQ counter timing specifically.
Visually, *Super Mario Bros. 3* is the acid test: it needs PRG/CHR banking *and* the
scanline IRQ for its status bar. *Kirby's Adventure* and *Mega Man 3–6* are good
follow-ups. (This is precisely the milestone that fixed the "Unsupported mapper" error
that motivated this whole tutorial.)

**Common bugs:** PRG bank register masking (modulo bank count) wrong; the two fixed
banks pointing at the wrong place in PRG mode 1; CHR 2 KB banks not ignoring the low
register bit; IRQ counter reload/decrement order wrong (the new-vs-old MMC3 behaviour,
§13.3); IRQ clocked at the wrong scanline phase.

---

## Milestone 7 — The APU (sound)

**Goal:** make sound. Deliberately last — nothing depends on it, and it's the hardest
to verify by ear.

Build the five channels as the counter state machines of §12, then the **frame
sequencer** that clocks their envelopes/sweeps/lengths, then the **non-linear mixer**
and **resampler** to 44.1 kHz (exact numbers in Appendix C). Add a tiny audio sink.

**Verification:** Blargg's `apu_test` and `apu_mixer` ROMs check channel behaviour and
the mix. By ear, the *SMB* overworld theme is the universal smoke test — wrong pitch
means your timer→frequency math is off; wrong *tempo* means your frame-sequencer step
counts are off; missing bass means the triangle channel is broken; harsh/clipping
sound means the non-linear mix or DC filter is wrong.

**Common bugs:** frame-sequencer firing at the wrong cycle offsets (Appendix C);
forgetting the triangle timer clocks every CPU cycle while pulse/noise clock every
*other* cycle; length-counter halt flag inverted; linear mixing instead of the
non-linear formula; resample ratio wrong.

---

# Appendix A — The 6502 opcode matrix

You can implement the 256-entry table two ways: as an explicit hand-written table
(what `Cpu6502.buildLookupTable()` does, and the safest choice), or by decoding the
opcode's bit-pattern. Understanding the *pattern* makes the table memorable and the
chip's design legible, even if you ultimately write it out explicitly.

**The `aaabbbcc` decomposition.** Treat the opcode byte as three fields:

```
  bit:  7 6 5 4 3 2 1 0
        a a a b b b c c
        └─aaa─┘ └bbb┘ └cc┘
        operation  mode  group
```

The low two bits `cc` pick a *group*; within a group, `bbb` selects the addressing
mode and `aaa` selects the operation. The largest and most regular group is **`cc=01`**
(the ALU/accumulator instructions). For it:

| `aaa` | op  |   | `bbb` | addressing mode      |
|------:|-----|---|------:|----------------------|
| 000   | ORA |   | 000   | `(zp,X)`  (IZX)      |
| 001   | AND |   | 001   | `zp`      (ZP0)      |
| 010   | EOR |   | 010   | `#imm`    (IMM)      |
| 011   | ADC |   | 011   | `abs`     (ABS)      |
| 100   | STA |   | 100   | `(zp),Y`  (IZY)     |
| 101   | LDA |   | 101   | `zp,X`    (ZPX)      |
| 110   | CMP |   | 110   | `abs,Y`   (ABY)     |
| 111   | SBC |   | 111   | `abs,X`   (ABX)     |

So `$65` = `0110 0101` = aaa=011 (ADC), bbb=001 (zp), cc=01 → `ADC $nn`. Check it
against the table in `Cpu6502.java:720`: `t[0x65] = ADC, ZP0`. It matches, and every
`cc=01` opcode follows this rule exactly. The `cc=10` group (shifts/`INC`/`DEC`/`LDX`/
`STX`) and `cc=00` group (branches, jumps, flag ops, `BIT`, `CP*`, `LD*` immediate)
are *mostly* regular with documented exceptions — which is why a hand table is safer.

**Branches** are their own elegant sub-pattern: opcode `xxy1 0000`. The top three bits
pick a flag and the value to test:

```
  10 ($10 BPL / $30 BMI)  → flag N    50/70 BVC/BVS → flag V
  90 ($90 BCC / $B0 BCS)  → flag C    D0/F0 BNE/BEQ → flag Z
```

**The complete official set** (56 operations) — implement all of these:

- *Load/store:* `LDA LDX LDY STA STX STY`
- *Transfer:* `TAX TAY TXA TYA TSX TXS`
- *Stack:* `PHA PHP PLA PLP`
- *Arithmetic/logic:* `ADC SBC AND ORA EOR CMP CPX CPY BIT`
- *Shift/rotate:* `ASL LSR ROL ROR`
- *Inc/dec:* `INC INX INY DEC DEX DEY`
- *Branch:* `BCC BCS BEQ BNE BMI BPL BVC BVS`
- *Jump/call:* `JMP JSR RTS RTI BRK`
- *Flags:* `CLC SEC CLI SEI CLV CLD SED`
- *Other:* `NOP`

For `nestest`'s full run you also need the common **unofficial** opcodes it exercises;
at minimum the duplicate `NOP`s of various lengths and the alternate `SBC` at `$EB`
(`Cpu6502.java:862`). The exact cycle counts per opcode are in the table; the rule of
thumb is zp=3, abs=4, indexed=4–5 (+1 on page cross for reads), RMW=5–7, branch=2
(+1 taken, +1 if it crosses a page).

---

# Appendix B — PPU timing reference

The frame is a **341-dot × 262-scanline** grid (§10.1). Lines: `-1` pre-render, `0–239`
visible, `240` post-render (idle), `241–260` VBlank. Per visible/pre-render line, the
fetch pipeline repeats every 8 dots:

```
 dots 1-256    : render pixels; fetch BG tiles (NT, AT, pattern-lo, pattern-hi) in 8-dot groups
 dot  256      : increment vertical scroll (fine Y / coarse Y)        [incrementScrollY]
 dot  257      : copy horizontal scroll bits from t to v             [transferAddressX]
                 + evaluate sprites for the next line
 dots 257-320  : fetch sprite patterns for the next line
 dots 280-304  : (pre-render line only) copy vertical bits t→v       [transferAddressY]
 dots 321-336  : fetch first two BG tiles of the next line
 dot  340      : last cycle; on odd frames with rendering on, line -1 is one dot shorter
```

**The flag-timing quirks that test ROMs check** (and that cause "almost works" bugs):

- **VBlank set:** scanline 241, dot 1. The NMI fires here *if* PPUCTRL bit 7 is set.
- **VBlank clear:** scanline −1 (pre-render), dot 1 — along with sprite-0 hit and
  overflow flags.
- **Reading `$2002`** clears the VBlank flag *and* the `$2005`/`$2006` write latch. There
  is a documented race if the read lands on the exact dot VBlank is set; Blargg's
  `ppu_vbl_nmi` checks it.
- **Buffered `$2007` reads:** a read of VRAM returns the *previously* buffered byte and
  then refills the buffer — except palette reads (`$3F00+`), which return immediately.
  Forgetting this breaks any game that reads back nametable data.
- **Odd-frame cycle skip:** when rendering is enabled, the pre-render scanline of odd
  frames skips its last dot, making that frame 89,341 dots instead of 89,342. This keeps
  the average frame rate correct and shifts the phase so vertical artifacts average out.

In this repo, all of the above lives in `Ppu.clock()` (`ppu/Ppu.java:335`); compare
your implementation against it dot for dot when a test fails.

---

# Appendix C — APU formulas and tables

**Clock rates.** The APU is clocked once per CPU cycle (~1.789773 MHz NTSC). The
triangle timer and DMC timer tick every CPU cycle; the two pulse timers and the noise
timer tick every *other* cycle (`Apu.clock()`, `apu/Apu.java:167`).

**Frame sequencer step offsets** (in CPU cycles within the period). This is the part
that determines musical *tempo*, so the exact numbers matter:

```
 4-step mode (period 29830 cycles, ~240 Hz quarter-frames):
   7457  → quarter-frame  (clock envelopes + triangle linear counter)
   14913 → quarter + half (half also clocks length counters + sweeps)
   22371 → quarter
   29829 → quarter + half + set frame IRQ (unless inhibited)

 5-step mode (period 37282 cycles):
   7457  → quarter
   14913 → quarter + half
   22371 → quarter
   37281 → quarter + half     (no IRQ in 5-step mode)
```

- *Quarter-frame* clocks: pulse/noise **envelopes** and the triangle **linear counter**.
- *Half-frame* clocks: **length counters** and the pulse **sweep** units.

**The non-linear mixer** (`emitSample()`, `apu/Apu.java:245`). The NES does not add the
channels linearly; louder combinations contribute proportionally less. With pulse
outputs 0–15 each and triangle/noise/DMC at their own ranges:

```
  pulse_out = 95.88 / ( 8128 / (pulse1 + pulse2) + 100 )          (0 if both pulses are 0)

  tnd = triangle/8227 + noise/12241 + dmc/22638
  tnd_out   = 159.79 / ( 1 / tnd + 100 )                          (0 if tnd is 0)

  sample    = pulse_out + tnd_out          → roughly 0.0 .. 1.0
```

Then a first-order **DC blocker** removes the large DC offset (and the startup pop)
before the sample goes to the sound card: `y = x - x_prev + 0.9995 * y_prev`.

**Lookup tables you'll need** (values in `apu/Apu.java:24`): the 32-entry **length
table**, the four 8-step **duty patterns** (12.5/25/50/75%), the 32-step **triangle
sequence** (15→0→15), the 16-entry **noise period** table, and the 16-entry **DMC rate**
table. These are fixed hardware constants — copy them exactly; there is nothing to
derive.

---

# Appendix D — A debugging playbook

When a milestone's test fails or the picture/sound is wrong, reason from the symptom.
Emulator bugs are remarkably consistent across implementations, so this table is most
of the diagnostic work:

| Symptom | Likely cause |
|---------|--------------|
| nestest diverges at one instruction | That opcode's operation or addressing mode; check flags first (V is the usual culprit) |
| nestest right but `CYC` drifts | Page-cross extra-cycle logic, or a branch's taken/page penalty |
| Whole screen is garbage/noise | CHR/pattern decoding, or nametable address math (loopy `v`) |
| Right shapes, wrong colours in 16×16 blocks | Attribute-byte quadrant shift/selection |
| Background scrolls but tears/jitters | `t`→`v` copy timing, or fine-X handling |
| Status bar scrolls with the level | Sprite-zero hit not firing, or firing at the wrong scanline |
| Sprites one line too high/low | Off-by-one in sprite Y compare; evaluate line N during N−1 |
| Sprites flicker when many on a line | Correct! That's the 8-per-line hardware limit |
| Game hangs at boot waiting forever | It's polling `$2002` for VBlank that never sets — PPU NMI/flag timing |
| Game resets randomly mid-level | Mapper IRQ (MMC3) firing wrong, or PRG bank glitch swapping out running code |
| Audio pitch wrong | Channel timer→frequency math |
| Audio tempo wrong | Frame-sequencer step counts |
| Audio harsh/clipping | Linear instead of non-linear mix, or missing DC filter |

**General technique.** When stuck, *reduce*. Disable sprites to isolate background.
Disable the mapper IRQ to isolate banking. Force a single palette colour to isolate
pattern decoding. Dump RAM or a specific address each frame to watch a value evolve.
And above all: **trust the layer below only if you verified it.** The reason M1 demands
a bit-exact nestest pass before anything else is that a CPU bug masquerades as a PPU
bug, an APU bug, and a mapper bug all at once — debugging upward from an unsound
foundation is the single most common way these projects die.

---

*You now have both halves: Part I, the mental model of every subsystem, and Part II,
the verified construction sequence. Build in order, prove each layer against its test
ROM, and you will have written a complete computer — one whose every byte you
understand, because you put it there.*
