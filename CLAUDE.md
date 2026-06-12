# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and run commands

```bash
# Build (compile + test + assemble jar)
gradle build

# Run with a ROM
gradle run --args="path/to/game.nes"

# Run tests
gradle test

# Run a specific test class
gradle test --tests "com.nesemu.cpu.Cpu6502Test"

# Run without Gradle (compiles to out/)
./run.sh path/to/game.nes
```

Requires **JDK 21+**. The gradle wrapper properties are present but the wrapper JAR is not bundled — use a locally installed `gradle`.

## Architecture

`NesSystem` is the central bus: it implements the `Bus` interface the CPU uses to read/write memory, and owns the master clock. The clock ratio is 3 PPU ticks per 1 CPU tick.

**CPU memory map** (in `NesSystem.cpuRead/cpuWrite`):
- `$0000–$1FFF` — 2 KB internal RAM, mirrored every `$0800`
- `$2000–$3FFF` — PPU registers, mirrored every 8 bytes
- `$4000–$4015` — APU registers (write-only except `$4015` status)
- `$4014` — OAM DMA (triggers 256-byte CPU→PPU OAM copy)
- `$4016/$4017` — Controller reads; `$4016` write strobes both pads; `$4017` write goes to APU frame counter
- `$4020–$FFFF` — Cartridge (PRG ROM/RAM via mapper)

**Timing model**: The CPU (`Cpu6502`) is instruction-stepped with cycle counting — memory accesses happen atomically per instruction, not spread across individual cycles. The PPU (`Ppu`) is dot-accurate. This hybrid is sufficient for sprite-zero hit timing used by Super Mario Bros.'s split-scroll status bar.

**Frame loop** (`NesSystem.stepFrame`): Called by the emulation thread in `EmulatorFrame`. Runs `clock()` in a tight loop until `Ppu.isFrameComplete()`. The emulation thread paces itself to ~60.0988 fps (NTSC); audio back-pressure from `AudioOutput` (a `SourceDataLine` that blocks when full) acts as an additional timing lock.

**OAM DMA** (`NesSystem.stepDma`): Writing `$4014` sets `dmaTransfer=true`. The DMA handler in `clock()` suspends the CPU, waits for an even cycle alignment (`dmaDummy`), then alternates read/write cycles to copy 256 bytes into PPU OAM.

**APU**: Clocked once per CPU cycle. Five channels (pulse×2, triangle, noise, DMC) are mixed with the NES non-linear formula, DC-filtered, and resampled to 44.1 kHz. Falls back to a timer if no audio device is available.

**Mapper abstraction**: `Cartridge` parses the iNES header and instantiates the appropriate `Mapper` subclass (`Mapper0` NROM, `Mapper4` MMC3). Unsupported mapper numbers throw `InvalidRomException` and show an error dialog. MMC3's scanline IRQ is driven from the PPU: `Ppu.clock()` calls `cartridge.clockScanlineCounter()` at cycle 260 of each rendered scanline (a per-scanline approximation of the hardware's PPU-A12 clocking), and `NesSystem.clock()` delivers `cartridge.isMapperIrqAsserted()` to `cpu.irq()` on the same level-triggered path as the APU IRQ.

## Key implementation details

- All integer registers/memory values are stored as Java `int` but treated as unsigned bytes/words — mask with `& 0xFF` or `& 0xFFFF` at boundaries.
- The `Bus` interface (`cpu/Bus.java`) is thin: just `cpuRead(int addr)` and `cpuWrite(int addr, int value)`. `NesSystem` is its sole production implementation.
- `Cpu6502` uses a table-driven design: a 256-entry `lookup[]` of `Instr` records (opcode → addressing mode micro + operation micro + cycle count).
- PPU nametable mirroring modes are in `cartridge/Mirroring.java`; the active mode is set by the mapper and read by the PPU when resolving nametable addresses.
