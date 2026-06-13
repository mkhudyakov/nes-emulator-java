# NES Emulator (Java)

An educational Nintendo Entertainment System emulator written from scratch in
**Java 21**. It implements the Ricoh 2A03 CPU (a 6502 core), the 2C02 PPU
(picture processing unit), the APU (audio processing unit), cartridge loading via
the iNES format, the NROM (Mapper 0) memory layout, controller input, and a
resizable Swing display. The design goal is to boot and play a legally-obtained
copy of *Super Mario Bros.* (1985) through World 1-1, with sound.

> **You must supply your own ROM.** No game ROM is included or distributed with
> this project. Only use ROM images of games you legally own.

---

## Requirements

- **JDK 21 or newer** (`java -version` should report 21+).
- Optionally **Gradle 8.x** if you prefer the Gradle workflow. Gradle is not
  required — convenience scripts are provided that use only the JDK.

## Running

### Option A — no build tool (simplest)

```bash
./run.sh path/to/your-game.nes        # macOS / Linux
run.bat  path\to\your-game.nes        # Windows
```

The script compiles the sources into `out/` and launches the emulator. You can
also start it with no argument and load a ROM from the **File → Open ROM…** menu.

### Option B — Gradle

If you have Gradle installed:

```bash
gradle run                            # opens the window
gradle run --args="--fullscreen /Users/claude/Downloads/mario.nes" 
gradle test                           # run the JUnit test suite
gradle build                          # compile + test + assemble
```

(A `gradle-wrapper.properties` is included, but the wrapper JAR is not bundled;
use a locally installed `gradle`, or run `gradle wrapper` once to generate the
wrapper.)

## Controls

| Action | Key          |
|--------|--------------|
| D-pad  | Arrow keys   |
| A      | Z            |
| B      | X            |
| Start  | Enter        |
| Select | Right Shift  |

Load a ROM, and use **Emulation → Reset** or **Pause / Resume** as needed.

---

## Architecture

The code is organised by hardware component under `com.nesemu`:

```
com.nesemu
├── Main                 Entry point; launches the Swing window.
├── NesSystem            The console: CPU memory bus + master clock.
├── cpu
│   ├── Bus              CPU's view of the address space (interface).
│   └── Cpu6502          Full official 6502 instruction set, IRQ/NMI/reset.
├── ppu
│   └── Ppu              Cycle-stepped 2C02: background, sprites, scrolling,
│                        sprite-zero hit, VBlank/NMI, NTSC palette.
├── apu
│   ├── Apu              Pulse ×2, triangle, noise, DMC; frame sequencer;
│   │                    non-linear mixer; resampling to the host rate.
│   └── AudioOutput      Java Sound sink (16-bit PCM, back-pressure pacing).
├── cartridge
│   ├── INesHeader       Parses the 16-byte iNES header.
│   ├── Cartridge        Loads PRG/CHR, builds the mapper.
│   ├── Mirroring        Nametable mirroring modes.
│   └── InvalidRomException
├── mapper
│   ├── Mapper           Abstract mapper interface.
│   ├── Mapper0          NROM: the layout Super Mario Bros. uses.
│   └── Mapper4          MMC3: PRG/CHR banking + scanline IRQ (SMB3, Mega Man 3-6).
├── controller
│   └── Controller       Standard pad with the 8-bit shift register.
└── ui
    ├── ScreenPanel      Scales the 256×240 framebuffer, aspect-correct.
    └── EmulatorFrame    Window, menus, key handling, emulation thread.
```

### How a frame is produced

`NesSystem` owns the master clock. On real hardware the PPU runs three times as
fast as the CPU, so each master tick clocks the PPU once and the CPU on every
third tick. The PPU renders one dot per tick across 341 dots × 262 scanlines.
When it enters VBlank (scanline 241) it raises an NMI, which the bus delivers to
the CPU — this is how games synchronise their game loop to the display. A write
to `$4014` triggers an OAM DMA: the bus stalls the CPU and copies 256 sprite
bytes into the PPU. `stepFrame()` runs the clock until the PPU signals a finished
frame, which the UI thread then draws and throttles to ~60 fps.

The APU advances one cycle for every CPU cycle. Its five channels are mixed with
the NES's non-linear formula, passed through a DC-blocking filter, and resampled
down to 44.1 kHz. Each frame's samples are handed to a `SourceDataLine`; when its
buffer fills, the write blocks just long enough to keep emulation locked to real
time, so audio doubles as the frame clock. If no sound device is available the
emulator falls back to a timer and runs silently.

### Timing model

The CPU is **instruction-stepped with cycle counting** (each instruction
consumes its nominal cycle budget), while the PPU is **dot-accurate**. This
combination is precise enough for the sprite-zero-hit timing that *Super Mario
Bros.* relies on for its split-scroll status bar, without the overhead of a fully
cycle-accurate CPU.

## Tests

JUnit 5 tests live under `src/test/java` and cover:

- **iNES header parsing** — field extraction, mapper-nibble combination, magic
  and size validation, CHR-RAM allocation.
- **6502 CPU** — reset vector, `LDA`/`STA`, `ADC` carry and overflow, `INX`
  wraparound, branch taken, `JSR`/`RTS`.
- **Mapper 0** — 16 KB PRG mirroring vs. 32 KB, PRG RAM, CHR RAM, address
  decoding.
- **Mapper 4 (MMC3)** — PRG bank modes, fixed last-two banks, 2 KB/1 KB CHR
  banking, A12 inversion, mirroring register, and the scanline IRQ counter.
- **Controller** — shift-register read order, post-eight reads returning 1,
  strobe-high reloading.
- **APU** — resampling rate, pulse/triangle producing audible output, disabled
  channels staying silent, the status register reflecting channel state.

Run them with `gradle test`.

## Scope

**Implemented:** CPU (official opcodes), PPU (background + sprites), APU (all five
channels with audio output), NROM (Mapper 0), MMC3 (Mapper 4, with its scanline
IRQ), controller input, Swing display, iNES loading.

**Out of scope (by design):** save states, rewind, netplay, cycle-perfect CPU
accuracy, and mappers other than 0 and 4. Loading an unsupported mapper raises a
clear error dialog.

## License / legal

This emulator is provided for educational purposes. It contains no Nintendo
code or assets. Supplying and using game ROMs is your responsibility; only use
images of games you own.
