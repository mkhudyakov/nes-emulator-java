# move-demo

A small, self-contained NES example: an original 16×16 character that **walks,
jumps, and collides** with solid objects on a single screen. No scrolling, no
enemies, no menus — just movement that feels right and solid collision, meant
as a clean starting point you can read top-to-bottom.

All graphics and code are original (MIT-licensed, see repository root).

> **New to NES programming?** [`TUTORIAL.md`](TUTORIAL.md) explains everything in
> this project from first principles — the hardware, the 6502, the assembler,
> and every part of the code — with line references you can follow along.

## Controls

| Button | Action |
|--------|--------|
| ← / →  | walk (accelerate / decelerate with friction) |
| A      | jump (hold for a higher jump, tap for a hop) |

## What it demonstrates

- 8.8 fixed-point position + velocity, with acceleration, friction and a top
  speed for snappy-but-weighty movement.
- Gravity and a **variable-height jump**.
- **Rectangle-based collision**, resolved one axis at a time, snapping the
  character flush against the ground, floating platforms, a tall pillar, and a
  block. A separate 1-pixel probe decides the grounded state so jumps are
  reliable.
- A static scene (sky, clouds, grass, platforms) drawn once into the nametable,
  and the character rendered as a 2×2 sprite with horizontal flipping and a
  walk/idle/jump animation.

## Build

Needs the **cc65** toolchain (`ca65` + `ld65`) and Python 3 (to generate the
CHR graphics).

```bash
cd games/move-demo
./build.sh            # -> build/move.nes
```

## Run

```bash
# from the repository root, using this repo's emulator:
gradle run --args="games/move-demo/build/move.nes"
```

## Files

```
move-demo/
├── src/move.s        the whole program (header, NMI, input, physics,
│                     collision, rendering) - one readable file
├── assets/gen_chr.py original tiles + the mascot sprite -> chr.bin
├── nrom.cfg          ld65 linker config (NROM-256)
├── build.sh
└── README.md
```

The scene's solid objects are just a list of rectangles near the bottom of
`move.s` (`rect_x/rect_y/rect_w/rect_h`); add or move a rectangle and it becomes
both drawn and collidable. Movement feel is governed by the `WALK_*`, `GRAVITY`,
and `JUMP_*` constants at the top.
