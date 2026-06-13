# move-demo: A Complete NES Programming Tutorial

This document explains **everything** in `move-demo` — a tiny NES program where
an original character walks, jumps, and collides with solid objects on one
screen. It assumes you can program (variables, loops, functions, binary/hex) but
have **never written NES or 6502 assembly**. By the end you'll understand every
line of `src/move.s` and `assets/gen_chr.py`, and you'll know enough to change
and extend it.

We'll build understanding bottom-up: the hardware, then the CPU, then the
assembler, then the program — boot, the frame loop, input, math, physics,
collision, and graphics. Code references look like `move.s:267`.

---

## Table of contents

1. [What the NES actually is](#1-what-the-nes-actually-is)
2. [The 6502 CPU in ten minutes](#2-the-6502-cpu-in-ten-minutes)
3. [The ca65 assembler](#3-the-ca65-assembler)
4. [The cartridge: iNES header & linker config](#4-the-cartridge-ines-header--linker-config)
5. [RAM layout](#5-ram-layout)
6. [Booting: the reset routine](#6-booting-the-reset-routine)
7. [The frame loop and the NMI](#7-the-frame-loop-and-the-nmi)
8. [Reading the controller](#8-reading-the-controller)
9. [Fixed-point numbers and signed 16-bit math](#9-fixed-point-numbers-and-signed-16-bit-math)
10. [Player physics](#10-player-physics)
11. [Collision detection](#11-collision-detection)
12. [Graphics part 1: tiles, CHR, and the pixel format](#12-graphics-part-1-tiles-chr-and-the-pixel-format)
13. [Graphics part 2: palettes and attributes](#13-graphics-part-2-palettes-and-attributes)
14. [Graphics part 3: drawing the background](#14-graphics-part-3-drawing-the-background)
15. [Graphics part 4: sprites and OAM](#15-graphics-part-4-sprites-and-oam)
16. [Build, run, and the war stories](#16-build-run-and-the-war-stories)
17. [Exercises](#17-exercises)

---

## 1. What the NES actually is

The Nintendo Entertainment System (1983) is three chips and some memory:

- **CPU** — a *2A03*, which is a **6502** (an 8-bit processor) with a built-in
  sound unit. It runs at ~1.79 MHz. "8-bit" means its registers hold one byte
  (0–255) and it natively does 8-bit math; anything bigger you do by hand.
- **PPU** (Picture Processing Unit) — a separate chip that draws a **256×240**
  image, 60 times a second, from its own video memory. The CPU can't draw
  pixels directly; it sends commands and data to the PPU through a few special
  memory addresses.
- **APU** (Audio Processing Unit) — sound. `move-demo` doesn't use it (silent),
  so we'll mostly ignore it.

The cartridge contains two ROMs:

- **PRG-ROM** — your program (the CPU's code and data).
- **CHR-ROM** — your graphics (the PPU's tiles).

The single most important idea: **the CPU and PPU run independently and talk
through a narrow mailbox.** A handful of addresses (`$2000`–`$2007`, `$4014`)
are not memory at all — writing them pokes the PPU. And the PPU only lets you
freely change what's on screen during a tiny window each frame called **vblank**
(the ~1/60s gap after the screen finishes drawing). Almost every NES quirk comes
from this arrangement.

### Numbers and notation

Assembly works in **hexadecimal** (base 16), written with a `$` prefix: `$FF`
= 255, `$2000` = 8192. Binary uses `%`: `%10001000` = `$88` = 136. A **byte** is
8 bits (0–255 unsigned, or −128..127 signed). A **word** is 16 bits (two bytes).
The 6502 is *little-endian*: a 16-bit value is stored low-byte-first.

---

## 2. The 6502 CPU in ten minutes

The 6502 has almost no registers. That's not a typo — three 8-bit registers:

- **A** (accumulator) — where arithmetic happens.
- **X** and **Y** — index registers, mostly for counting and array offsets.

Plus: **PC** (program counter, 16-bit), **SP** (stack pointer, the stack lives at
`$0100–$01FF`), and **P**, the *status register*, a bag of 1-bit **flags** set as
a side effect of most instructions:

- **Z** (zero) — was the last result zero?
- **C** (carry) — carry-out of an add, or "no borrow" after a subtract/compare.
- **N** (negative) — bit 7 of the last result (the sign bit).
- plus V (overflow), I, D, B — minor here.

You don't read flags directly; you **branch** on them.

### The instructions used in this program

You only need to recognize a couple dozen:

| Mnemonic | Meaning |
|----------|---------|
| `lda/ldx/ldy #v` | load A/X/Y with a value |
| `sta/stx/sty addr` | store A/X/Y to memory |
| `tax/tay/txa/tya` | copy between A and X/Y |
| `adc` / `sbc` | add / subtract **with carry** (the only add/sub there is) |
| `clc` / `sec` | clear / set the carry flag (do this *before* adc/sbc) |
| `and/ora/eor #v` | bitwise AND / OR / XOR |
| `asl/lsr` | shift left / right by one bit (×2 / ÷2) |
| `rol/ror` | rotate left/right through carry |
| `inc/dec addr` | add/subtract 1 in memory |
| `cmp/cpx/cpy` | compare (subtract without storing; sets Z, C, N) |
| `beq/bne` | branch if Z set / clear (equal / not equal) |
| `bcc/bcs` | branch if carry clear / set |
| `bmi/bpl` | branch if N set / clear (minus / plus) |
| `jmp` | goto |
| `jsr` / `rts` | call subroutine / return |
| `pha/pla` | push/pull A on the stack |
| `bit` | test bits (used here to read a PPU flag) |
| `rti` | return from interrupt |

Two things trip up newcomers:

**Carry is part of every add and subtract.** There is no plain "add." `adc`
computes `A = A + operand + carry`. So before a fresh addition you `clc` (carry =
0); before a fresh subtraction you `sec` (carry = 1, meaning "no borrow yet").
This is also exactly how you chain bytes into bigger numbers: add the low bytes
(carry comes out), then `adc` the high bytes (that carry flows in). You'll see
this 16-bit pattern everywhere below.

**Compare sets carry the "≥" way.** After `cmp X`: carry is **set if A ≥ X**
(unsigned), clear if A < X, and Z is set if equal. So `cmp X / bcs` means "branch
if A ≥ X." This is unsigned — which becomes a famous footgun with signed numbers
(see §16).

### Addressing modes

How an instruction names its operand:

- **Immediate** `lda #$05` — the literal value 5.
- **Zero page** `lda px` — read address `$00xx`. The first 256 bytes of RAM are
  "zero page," and accessing them is **faster and smaller** (2 bytes instead of
  3). That's why hot variables live there.
- **Absolute** `lda $0200` — any 16-bit address.
- **Indexed** `lda rect_x,x` — address + X. This is array indexing: `rect_x` is a
  table, `X` is the element number.

---

## 3. The ca65 assembler

We use **ca65** (assembler) and **ld65** (linker) from the [cc65 suite]. The
assembler turns `move.s` into an object file; the linker places it at real
addresses and emits the `.nes` file according to `nrom.cfg`.

ca65 directives you'll see:

- `NAME = value` — a compile-time constant. `move.s:15` `PPUCTRL=$2000`.
- `.segment "NAME"` — choose which output section following code/data goes into
  (the linker decides where each segment lands; see §4).
- `label:` — a name for the current address.
- `.proc name … .endproc` — a labelled scope. `name` is callable with `jsr name`;
  labels inside are private to the proc, so every proc can reuse `@loop`.
- `@label` — a *cheap local* label, valid until the next normal label. Lets you
  write `@loop`, `@done` repeatedly without clashes.
- `:` — an *anonymous* label. `bne :-` branches to the nearest previous `:`,
  `beq :+` to the next one. Handy for tiny loops.
- `.res n` — reserve `n` bytes (for RAM variables).
- `.byte`, `.addr` — emit literal bytes / 16-bit addresses into ROM.
- `.incbin "file"` — paste a binary file in (we use it for the graphics).
- `#<value` / `#>value` — the **low** / **high** byte of a 16-bit value. Because
  registers are 8-bit, you load a pointer in two halves: `lda #<addr` then
  `lda #>addr`. `move.s:243` uses `#<(0 - JUMP_VEL)` / `#>(0 - JUMP_VEL)` to split
  a negative 16-bit constant into two bytes.

---

## 4. The cartridge: iNES header & linker config

A `.nes` file (the "iNES" format) is just: a **16-byte header**, then the
PRG-ROM, then the CHR-ROM. Our header is `move.s:44`:

```asm
.segment "HEADER"
    .byte "NES", $1A, $02, $01, $00, $00
    .byte 0,0,0,0,0,0,0,0
```

Byte by byte: `"NES",$1A` is the magic signature. `$02` = PRG size in 16 KB
units → **32 KB**. `$01` = CHR size in 8 KB units → **8 KB**. The next `$00`
(flags 6) encodes the **mapper** number's low nibble (0) and mirroring; `$00`
(flags 7) the high nibble. Mapper **0** ("NROM") means *no bank switching* — the
whole 32 KB is always visible. The trailing zeros pad the header to 16 bytes.

Where does each segment go in the CPU's address space? That's `nrom.cfg`:

```
MEMORY {
    ZP:     start=$0000 size=$0100 type=rw file="";   # zero page (RAM, not in file)
    RAM:    start=$0300 size=$0400 type=rw file="";   # general RAM (not in file)
    HEADER: start=$0000 size=$0010 type=ro file=%O;   # 16-byte header -> file
    PRG:    start=$8000 size=$8000 type=ro file=%O;   # 32 KB code/data -> file
    CHR:    start=$0000 size=$2000 type=ro file=%O;   # 8 KB graphics -> file
}
SEGMENTS {
    HEADER  -> HEADER
    ZEROPAGE-> ZP        (your .segment "ZEROPAGE" vars)
    BSS     -> RAM       (your .segment "BSS" vars)
    CODE    -> PRG start=$8000
    RODATA  -> PRG       (read-only tables)
    VECTORS -> PRG start=$FFFA
    CHARS   -> CHR       (the .incbin graphics)
}
```

Key facts this encodes:

- The CPU sees PRG-ROM at **`$8000`–`$FFFF`**. Code lives there; it's read-only.
- The very top six bytes, **`$FFFA`–`$FFFF`**, are the **interrupt vectors** —
  three 16-bit addresses the CPU jumps to on NMI, RESET, and IRQ. We fill them
  at `move.s:969`:
  ```asm
  .segment "VECTORS"
      .addr nmi, reset, irq
  ```
  So at power-on the CPU reads `$FFFC/$FFFD` and jumps to `reset`. Every vblank
  it jumps to `nmi`. This is how execution begins and how the frame interrupt
  finds its handler.
- `file=""` segments (ZP, RAM) are **RAM** — they exist at runtime but aren't
  stored in the file (there's nothing to store; it's variables).

The resulting file is 16 + 32768 + 8192 = **40,976 bytes**.

---

## 5. RAM layout

The NES has 2 KB of CPU RAM, `$0000–$07FF`, which we divide up (`move.s:49`):

| Range | Use |
|-------|-----|
| `$0000–$00FF` | **Zero page** — our variables (fast access) |
| `$0100–$01FF` | the **stack** (the CPU uses it for `jsr`/`pha`) |
| `$0200–$02FF` | the **OAM shadow** — a copy of sprite data we DMA to the PPU |
| `$0300–$07FF` | general RAM (this program barely uses it) |

Our variables (`move.s:49–78`) are declared with `.res` in the `ZEROPAGE`
segment, so the linker assigns them consecutive zero-page addresses:

```asm
px:    .res 1   ; player X, whole pixels
px_sub:.res 1   ; player X, fractional part (1/256 px)
py/py_sub        ; same for Y
vx:    .res 2   ; X velocity, signed 16-bit
vy:    .res 2   ; Y velocity, signed 16-bit
on_ground/facing/anim ...
r_x/r_y/r_w/r_h/ri  ; scratch used while testing one collision rectangle
tmp1..tmp4, ptr     ; general scratch
```

`OAM = $0200` (`move.s:41`) is a plain constant naming the sprite shadow page.

---

## 6. Booting: the reset routine

When the console powers on, the CPU jumps to `reset` (`move.s:86`). The PPU isn't
ready for ~1 frame, and RAM holds garbage, so the boot dance is fixed ritual:

```asm
sei            ; ignore IRQ interrupts
cld            ; clear "decimal mode" (the NES's 6502 ignores it, but be safe)
ldx #$40
stx APUFRAME   ; disable the APU's frame interrupt
ldx #$FF
txs            ; set the stack pointer to $01FF (stack grows downward)
inx            ; X = 0
stx PPUCTRL    ; turn the PPU's NMI off
stx PPUMASK    ; turn rendering off (screen blank) while we set up
stx APUSTATUS  ; silence the APU
```

Then it waits for the PPU to warm up by polling `PPUSTATUS` (`$2002`). Reading
that register returns bit 7 = "in vblank." `bit PPUSTATUS / bpl :-` loops until
bit 7 is set (`bit` copies bit 7 into N; `bpl` = branch while N clear):

```asm
:   bit PPUSTATUS
    bpl :-
```

Between the two required vblank waits, it clears all of RAM to 0 and sets every
sprite's Y to `$FF` (off the bottom of the screen, i.e. hidden) — `move.s:99–113`.
That loop writes 256 bytes to each page using `X` from 0 to 255:

```asm
    lda #0
    ldx #0
:   sta $0000,x
    sta $0300,x
    ...
    inx
    bne :-       ; X wraps 255->0, so this runs exactly 256 times
```

Finally it builds the screen with rendering still off (you can only freely write
PPU memory while it's not drawing), then switches the PPU on:

```asm
jsr load_palette   ; upload colours
jsr draw_scene     ; paint the background tiles
jsr init_player    ; place the character
lda #%10001000     ; PPUCTRL: enable NMI (bit7), sprites use pattern table 1 (bit3)
sta PPUCTRL
lda #%00011110     ; PPUMASK: show background + sprites
sta PPUMASK
```

`PPUCTRL = %10001000`: bit 7 turns the vblank **NMI** on (now `nmi` fires 60×/s),
bit 3 says sprites read their pixels from **pattern table 1** (`$1000`). `PPUMASK
= %00011110` enables background and sprite rendering and disables the left-column
clipping. After this, control falls into the main `loop`.

---

## 7. The frame loop and the NMI

The PPU draws the screen continuously. You may only push large updates (palettes,
the whole background) during **vblank**. But small per-frame updates — moving
sprites, setting the scroll — also need to land in vblank or you get tearing. The
classic structure: **do all game logic in the visible part of the frame, writing
results into RAM buffers; then, in the vblank NMI, copy those buffers to the
PPU.**

The main loop (`move.s:126`):

```asm
loop:
    jsr read_pad        ; 1. read the controller
    jsr update_player   ; 2. physics + collision (updates px,py and the OAM shadow)
    jsr draw_player     ; 3. write the 4 sprites into the $0200 shadow
    lda frame           ; 4. wait until the NMI has run once...
:   cmp frame
    beq :-
    jmp loop
```

`frame` is a counter the NMI increments every vblank. The loop snapshots it, then
spins until it changes — that's "wait for the next frame." Logic for frame *N* is
prepared, then presented by the NMI.

The NMI handler (`move.s:137`) runs during vblank:

```asm
nmi:
    pha / txa pha / tya pha     ; save A,X,Y (an interrupt must not clobber them)
    lda #0
    sta OAMADDR
    lda #>OAM
    sta OAMDMA                  ; **sprite DMA**: copy 256 bytes from $0200 -> PPU OAM
    bit PPUSTATUS               ; reset the PPU's address latch
    lda #0
    sta PPUSCROLL
    sta PPUSCROLL               ; scroll = (0,0): no scrolling in this demo
    lda #%10001000
    sta PPUCTRL
    lda #%00011110
    sta PPUMASK
    inc frame                   ; tell the main loop a frame elapsed
    pla tay / pla tax / pla     ; restore A,X,Y
    rti
```

The crucial line is the **OAM DMA**: writing the page number (`$02`) to `$4014`
makes the hardware copy all 256 bytes of `$0200–$02FF` into the PPU's sprite
memory in one shot. That's why we assemble sprites into the `$0200` shadow during
the frame and only "publish" them here.

`irq` (`move.s:164`) is just `rti` — we don't use IRQs.

---

## 8. Reading the controller

A standard controller is a shift register. You **strobe** it (write 1 then 0 to
`$4016`) to latch the current button states, then read `$4016` eight times; each
read returns the next button in bit 0, in the order A, B, Select, Start, Up,
Down, Left, Right.

`read_pad` (`move.s:171`):

```asm
lda pad
sta pad_prev        ; remember last frame's buttons (for edge detection)
lda #1
sta JOY1
lda #0
sta JOY1            ; strobe: 1 then 0 latches the buttons
ldx #8
lda #0
sta pad
:   lda JOY1        ; read one button into bit 0
    lsr             ; shift that bit into carry
    rol pad         ; rotate carry into pad from the right
    dex
    bne :-          ; repeat 8 times
```

After 8 rotations, `pad` holds all eight buttons. Because A came first and got
rotated left seven more times, **A ends up in bit 7**, B in bit 6, … Right in bit
0 — which is exactly the layout of the `BTN_*` constants (`move.s:28`): `BTN_A =
$80`, `BTN_RIGHT = $01`, etc. So "is A held?" is `lda pad / and #BTN_A`.

The last three lines compute **edge detection** — buttons *newly pressed this
frame*:

```asm
lda pad
eor pad_prev    ; bits that changed
and pad         ; ...and are now pressed
sta pad_press   ; = pressed this frame but not last frame
```

We use `pad` (held) for movement (you hold a direction) and `pad_press` (just
pressed) for jumping (one jump per press) — `move.s:238`.

---

## 9. Fixed-point numbers and signed 16-bit math

The character must move at fractional speeds (e.g. 1.9 px/frame), but the CPU
only does integers. The fix is **fixed point**: store position as
`whole.fraction`, where the fraction is in units of 1/256.

- Position X is **`px` (whole pixels) + `px_sub` (1/256ths)** — together a 16-bit
  number, `px` the high byte, `px_sub` the low byte. This is "8.8 fixed point."
- Velocity `vx` is a **signed 16-bit** number, also in 1/256 px. So `WALK_MAX =
  480` means 480/256 = **1.875 px/frame**.

Moving is then just adding the velocity to the position as 16-bit numbers
(`move_x`, `move.s:393`):

```asm
clc
lda px_sub
adc vx+0        ; add low bytes (fraction)
sta px_sub
lda px
adc vx+1        ; add high bytes (whole pixels) + carry from the fraction
sta px
```

`vx+0` / `vx+1` are the two bytes of `vx` (ca65 lets you offset a label). That
two-step add — low bytes, then high bytes with the carry — **is** 16-bit
addition. The same pattern adds gravity to `vy`, accelerates `vx`, etc.

### Negative numbers

Signed bytes use **two's complement**: −1 is `$FF`, −256 is `$FF00`. The high
byte's bit 7 is the sign. To negate a 16-bit number you flip all bits and add 1,
or — as the code does for constants — let the assembler compute it: `0 - JUMP_VEL`
is `−1000` = `$FC18`, and `#<` / `#>` split it into `$18` and `$FC`
(`move.s:243`). Loading those two bytes into `vy` sets an *upward* velocity.

### Comparing 16-bit numbers

To compare two words you subtract and look at the carry:

```asm
lda #<LIMIT
cmp value+0     ; subtract low bytes (discard result, keep carry)
lda #>LIMIT
sbc value+1     ; subtract high bytes with borrow
bcs in_range    ; carry set => LIMIT >= value
```

This is how `vx_right` clamps to `WALK_MAX` (`move.s:285`) and `apply_gravity`
clamps to `MAX_FALL` (`move.s:364`). **But this is an *unsigned* comparison**, and
that subtlety caused two real bugs — see §16. The guards `bmi @ok` at `move.s:284`
and `move.s:362` exist precisely to skip the clamp when the value is negative.

---

## 10. Player physics

`update_player` (`move.s:217`) runs once per frame and is the heart of the feel.
Its order is: horizontal input → jump → variable-jump cut → gravity → move &
collide X → move & collide Y.

### Horizontal: acceleration and friction

Holding a direction doesn't set a fixed speed; it **accelerates** toward a top
speed, and releasing decelerates via **friction**. That little bit of ramp-up is
what makes movement feel weighty instead of robotic.

```asm
lda pad
and #BTN_LEFT
beq @notleft
    lda #1
    sta facing          ; remember we face left (for the sprite)
    jsr vx_left         ; vx -= accel, clamped to -WALK_MAX
    jmp @vert
@notleft:
lda pad
and #BTN_RIGHT
beq @noh
    lda #0
    sta facing
    jsr vx_right        ; vx += accel, clamped to +WALK_MAX
    jmp @vert
@noh:
    jsr vx_friction     ; no input: ease vx toward 0
```

- `vx_right` (`move.s:276`): 16-bit `vx += WALK_ACCEL`, then clamp to `+WALK_MAX`.
- `vx_left` (`move.s:298`): `vx -= WALK_ACCEL`, then clamp to `−WALK_MAX`. Its
  clamp checks `vx + WALK_MAX`; if that's negative, vx went past the limit.
- `vx_friction` (`move.s:320`): if `vx` is positive, subtract `FRICTION` but don't
  cross zero; if negative, add `FRICTION` toward zero; snapping to exactly 0 when
  it would overshoot. This is why the character coasts to a stop.

### Jumping

```asm
@vert:
lda pad_press
and #BTN_A
beq @nojump        ; A not newly pressed
lda on_ground
beq @nojump        ; not standing on anything
    lda #<(0 - JUMP_VEL)   ; vy = -1000  (upward)
    sta vy+0
    lda #>(0 - JUMP_VEL)
    sta vy+1
    lda #0
    sta on_ground
@nojump:
```

A jump is just "set an upward velocity, once, if grounded." Using `pad_press`
(not `pad`) means holding A doesn't re-trigger; you must press again.

### Variable jump height

A hallmark of good platformers: tap A for a small hop, hold for a full jump. We
implement it by **cutting upward speed the moment A is released while still
rising** (`move.s:250`):

```asm
lda pad
and #BTN_A
bne @nocut          ; A still held -> let the jump run
lda vy+1
bpl @nocut          ; only meaningful while vy is negative (rising)
; if vy is more upward than -JUMP_CUT, clamp it to -JUMP_CUT
lda vy+0
cmp #<(0 - JUMP_CUT)
lda vy+1
sbc #>(0 - JUMP_CUT)
bpl @nocut
    lda #<(0 - JUMP_CUT)
    sta vy+0
    lda #>(0 - JUMP_CUT)
    sta vy+1
@nocut:
```

So a quick tap chops the rise short; holding lets the full `JUMP_VEL` play out
against gravity. Tuning `JUMP_VEL`, `GRAVITY`, and `JUMP_CUT` changes the whole
feel.

### Gravity

`apply_gravity` (`move.s:354`) adds `GRAVITY` to `vy` every frame and clamps the
*downward* speed to `MAX_FALL` so you don't fall arbitrarily fast:

```asm
clc
lda vy+0
adc #<GRAVITY
sta vy+0
lda vy+1
adc #>GRAVITY
sta vy+1
bmi @ok             ; rising (vy<0): never clamp (this guard is essential, see §16)
; clamp positive vy to MAX_FALL ...
```

Because `vy` is signed and the clamp compare is unsigned, the `bmi @ok` guard is
what keeps an upward velocity from being mistaken for "faster than max fall" and
destroyed. (That was the jump-killing bug.)

---

## 11. Collision detection

The world's solid objects are a **list of rectangles** — simpler and more exact
than a tile grid for a handful of platforms. They're parallel arrays in ROM
(`move.s:957`):

```asm
NUM_RECTS = 6
;            ground  platA  platB  platC  pillar  block
rect_x:    .byte   0,   48,  144,   96,  208,   24
rect_y:    .byte 208,  176,  144,  112,  160,  140
rect_w:    .byte 240,   64,   48,   32,   16,   16
rect_h:    .byte  32,    8,    8,    8,   56,   16
rect_tile: .byte $02,  $04,  $04,  $04,  $03,  $03   ; which tile draws each one
```

The character's **hitbox** is a box slightly inset from its 16×16 sprite
(`move.s:381`): left `+2`, right `+13`, top `+1`, bottom `+15`.

### Move one axis at a time, then push out

The golden rule of simple platformer collision: **resolve X and Y separately.**
Move horizontally, fix horizontal overlaps; then move vertically, fix vertical
overlaps. Doing both at once makes corners ambiguous.

`move_x` (`move.s:386`): add `vx`, clamp to the screen, then for each rectangle,
if the hitbox overlaps, shove the character flush against the side it came from:

```asm
jsr overlap
bcc @next           ; no overlap with this rect
lda vx+1
bmi @hitleft        ; moving left -> hit a right wall
    ; moving right -> snap right edge flush to the rect's left edge
    lda r_x
    sec
    sbc #(HB_R+1)
    sta px
    jmp @stop
@hitleft:
    lda r_x
    clc
    adc r_w
    sec
    sbc #HB_L       ; snap left edge flush to the rect's right edge
    sta px
@stop:
    lda #0
    sta px_sub
    sta vx+0
    sta vx+1        ; kill horizontal velocity and the sub-pixel remainder
```

`move_y` (`move.s:453`) is the mirror image: snap to the **top** of a rect when
falling (landing) or to the **bottom** when rising (bonking your head), and zero
`vy`.

### The grounded probe

One subtlety made jumps unreliable at first. When you land, the snap puts the
character's feet exactly on the surface — but then it's *not overlapping*
anymore. With sub-pixel gravity, the overlap only re-occurs every few frames (when
the fraction rolls over a whole pixel), so "am I on the ground?" flickered, and
jumps (which require `on_ground`) usually failed.

The fix (`move.s:499`) is a dedicated **1-pixel-down probe**, run after vertical
movement: temporarily nudge the character down one pixel, test all rectangles,
set `on_ground` if any overlap, then nudge back:

```asm
ground_probe:
    lda #0
    sta on_ground
    inc py              ; pretend to be 1px lower
    ; ... test every rect; if overlap, on_ground = 1 ...
    dec py              ; restore
```

Decoupling "grounded?" from the movement overlap makes resting on a surface read
as grounded **every** frame. This is a standard platformer technique.

### The overlap test

`overlap` (`move.s:538`) is axis-aligned bounding-box (AABB) intersection: two
boxes overlap iff they overlap on **both** axes, and they overlap on an axis iff
neither is entirely to one side of the other. Player box is `[px+2 .. px+13]` ×
`[py+1 .. py+15]`; rect box is `[r_x .. r_x+r_w−1]` × `[r_y .. r_y+r_h−1]`. The
code uses the convention that `r_x + r_w` is "one past the right edge," so:

```
no overlap if  player_left  >= r_x + r_w     (player entirely right of rect)
            or r_x          >  player_right   (rect entirely right of player)
            or player_top   >= r_y + r_h
            or r_y          >  player_bottom
otherwise: overlap
```

Each clause is a `cmp` plus a `bcs`/`beq`. It returns the answer in the **carry**
flag (set = overlapping), which is why callers do `jsr overlap / bcc @next`.

---

## 12. Graphics part 1: tiles, CHR, and the pixel format

The PPU can't draw arbitrary pixels — it draws **8×8 tiles** from the CHR-ROM.
There are two "pattern tables" of 256 tiles each: table 0 (`$0000`) and table 1
(`$1000`). In this demo, background tiles come from table 0 and sprites from
table 1 (that's what `PPUCTRL` bit 3 selected).

Each pixel is **2 bits** → one of 4 colours (0–3). A tile is therefore 8×8×2 bits
= 16 bytes, stored as two **bit planes**: 8 bytes giving each pixel's low bit,
then 8 bytes giving each pixel's high bit. Pixel value = `(plane1_bit << 1) |
plane0_bit`.

We don't write that by hand; `assets/gen_chr.py` builds it. The `tile()` helper
takes eight strings of eight digits (`'0'..'3'`) and packs them:

```python
def tile(rows):
    lo, hi = [], []
    for r in rows:                 # each row is 8 chars '0'..'3'
        a = b = 0
        for px in r:
            v = int(px)
            a = (a << 1) | (v & 1)        # low bit of this pixel
            b = (b << 1) | ((v >> 1) & 1) # high bit
        lo.append(a); hi.append(b)
    return bytes(lo + hi)          # 8 low-plane bytes, then 8 high-plane bytes
```

The script writes background tiles (sky, grass, dirt, brick, platform, cloud)
into the first 4 KB and the 16×16 mascot into table 1. A 16×16 character is **four
8×8 tiles** (top-left, top-right, bottom-left, bottom-right); `put16()` slices a
16×16 grid into those four tiles and stores them at consecutive indices (base+0..
base+3). It generates four animation poses — idle (`$00`), two walk frames (`$04`,
`$08`), and a jump frame (`$0C`). The final file is exactly 8192 bytes and is
pasted into the ROM by `.incbin "../assets/chr.bin"` (`move.s:973`).

---

## 13. Graphics part 2: palettes and attributes

Tiles only store colour *indices* 0–3. The actual colours come from **palette
RAM** at `$3F00`: eight 4-colour palettes (4 for backgrounds, 4 for sprites),
each colour a byte selecting one of the NES's 64 fixed master colours. Our
palette table is `move.s:943`; `load_palette` (`move.s:746`) uploads all 32 bytes:

```asm
bit PPUSTATUS       ; reset the address latch
lda #$3F
sta PPUADDR         ; set PPU write address high byte
lda #$00
sta PPUADDR         ; ...and low byte -> $3F00
ldx #0
:   lda palette,x
    sta PPUDATA     ; PPUDATA auto-increments the address after each write
    inx
    cpx #32
    bne :-
```

Two gotchas worth knowing (one bit us — see §16):

- **`$3F00` is the universal backdrop**, and `$3F10/$3F14/$3F18/$3F1C` (the
  colour-0 slots of the sprite palettes) are **mirrors** of the background
  backdrop slots. So colour 0 of every palette must agree. We set them all to
  `$22` (sky blue) — note every palette in `move.s:943` starts with `$22`.
- Background colour 0 is also "transparent to the backdrop." Our blank sky tile is
  all colour 0, so it just shows `$22`.

**Attributes** decide *which* of the four background palettes each part of the
screen uses. The catch: the resolution is coarse — one attribute byte covers a
**32×32 pixel** region (a 4×4 block of tiles), split into four 16×16 quadrants,
2 bits each. The 64 attribute bytes live right after each nametable, at `$23C0`.

`draw_scene` clears all attributes to 0 (palette 0 everywhere), then sets the
**top attribute row** to palette 1 (`move.s:788`) so the cloud tiles (which use
colour 1) come out white instead of green:

```asm
lda #$55            ; %01010101: all four quadrants -> palette 1
ldy #8
:   sta PPUDATA     ; 8 bytes = top 32 px of the screen
    dey
    bne :-
```

(That's why `palette` index 1 is white-heavy and the top of the sky uses it.)

---

## 14. Graphics part 3: drawing the background

The background is a grid of tile indices called a **nametable** at `$2000`: 32×30
tiles = 960 bytes, followed by the 64 attribute bytes. We paint it once at boot
(rendering off) in `draw_scene` (`move.s:762`):

1. Fill the whole nametable with the blank sky tile `$00` (1024 writes).
2. Set attributes (palette 0 everywhere, palette 1 on the top row).
3. Draw the clouds.
4. For each solid rectangle, stamp its tiles.
5. Lay a grass strip on the ground's top row.

The interesting routine is `fill_rect_tiles` (`move.s:823`), which converts a
pixel rectangle into nametable writes. Tiles are 8 px, so `tx = r_x/8`, `ty =
r_y/8`, width/height in tiles = `r_w/8`, `r_h/8` (each `/8` is three `lsr`s).
For each tile row it has to compute the nametable address:

```
addr = $2000 + ty*32 + tx
```

It builds that 16-bit address as a high and low byte. Since `ty*32` spans the
high byte (32 tiles per row, 8 rows per 256 bytes):

```asm
; low  byte = ((ty & 7) << 5) | tx        ; position within a 256-byte chunk
; high byte = $20 + (ty >> 3)             ; which 256-byte chunk of the nametable
```

then writes the address to `PPUADDR` (high then low) and streams `tw` copies of
the tile to `PPUDATA` (which auto-increments). `draw_clouds` (`move.s:918`) is the
same idea with pre-computed addresses in a small table (`move.s:965`).

Because nothing scrolls, this background never has to be touched again — the NMI
just keeps showing it.

---

## 15. Graphics part 4: sprites and OAM

Movable objects are **sprites**, described in **OAM** (Object Attribute Memory),
64 entries of 4 bytes each:

| Byte | Meaning |
|------|---------|
| 0 | **Y** position (note: the sprite displays one scanline *below* this value) |
| 1 | **tile** index (from pattern table 1 here) |
| 2 | **attributes**: bits 0–1 palette, bit 5 priority, bit 6 flip-H, bit 7 flip-V |
| 3 | **X** position |

We don't write OAM directly during the frame; we fill the `$0200` **shadow** and
let the NMI DMA it across (§7). `draw_player` (`move.s:588`) builds the four
sprites of the 16×16 character every frame:

1. **Pick the pose.** If airborne, use the jump tile `$0C`. Else if moving (vx ≠
   0), alternate walk frames `$04`/`$08` using bit 3 of the `anim` counter (which
   increments each frame, so the frame flips a few times a second). Else idle
   `$00` (`move.s:592–610`).
2. **Emit four sprites** at `(px,py)`, `(px+8,py)`, `(px,py+8)`, `(px+8,py+8)`
   with tiles base+0..base+3 (TL, TR, BL, BR). `put_sprite` (`move.s:708`) appends
   one OAM entry and advances the write cursor `oami` by 4.
3. **Facing.** When facing left, `draw_player` jumps to `@flip`, which uses
   `put_sprite_f` (sets attribute bit 6, horizontal flip) **and** swaps the
   left/right tiles, so the mirrored character looks correct (`move.s:660`).

Why don't we clear the other 60 sprites each frame? Because only the player uses
sprites and it always writes the same first four slots; the rest were set to
`Y=$FF` (off-screen) once at boot and never touched. The `frame` counter and the
sprite Y of `$FF` are the only two "hidden" pieces of state keeping the screen
tidy.

---

## 16. Build, run, and the war stories

### Build & run

```bash
cd games/move-demo
./build.sh                                  # gen_chr.py + ca65 + ld65 -> build/move.nes
gradle run --args="games/move-demo/build/move.nes"   # this repo's emulator
# or: fceux build/move.nes
```

`build.sh` runs the Python graphics generator, assembles `move.s` to an object
file, and links it with `nrom.cfg` into a 40,976-byte `.nes`.

### Three bugs this program had (and what they teach)

These are real bugs that occurred while writing this demo. They're the best part
of the tutorial because they're the mistakes you'll make too.

**1. The black screen (palette mirroring).** Setting each sprite palette's
colour-0 to black made the *background* turn black. Cause: `$3F10/$14/$18/$1C`
mirror the background backdrop slots `$3F00/$04/$08/$0C`. *Lesson:* colour 0 of
every palette is the shared backdrop — keep them identical.

**2. The character couldn't pass mid-screen (signed test on unsigned data).** The
X-clamp originally used `bmi` ("is it negative?") to detect underflow past the
left edge. But `px` is **unsigned** 0–255, and `bmi` just checks bit 7 — so any
`px ≥ 128` looked "negative" and got snapped back. *Lesson:* `bmi`/`bpl` test bit
7; that only means "negative" if you're treating the byte as signed. The fix
(`move.s:405`) decides over/underflow from the travel **direction** instead.

**3. The jump did nothing (unsigned compare clamped an upward velocity).**
`apply_gravity` clamped `vy` to `MAX_FALL` using a 16-bit compare — which is
**unsigned**. A jump sets `vy` negative (`$FC18`), which as an unsigned number is
huge, so the clamp "corrected" it to maximum *downward* speed, erasing the jump
the same frame. *Lesson:* the 6502's `cmp`/`sbc` comparisons are unsigned; when a
value can be negative, guard the clamp with a sign check first (`bmi @ok`,
`move.s:362`). The identical guard was added to `vx_right` (`move.s:284`).

The throughline: **the 6502 has no concept of "signed" — you do.** Bit 7 is only
a sign because you decide to treat it that way, and `cmp` is always unsigned.
Most early platformer bugs are some version of this.

---

## 17. Exercises

Small, in rough order of difficulty:

1. **Tune the feel.** Change `WALK_MAX`, `WALK_ACCEL`, `GRAVITY`, `JUMP_VEL`,
   `JUMP_CUT` (`move.s:33`) and rebuild. Try a floaty moon jump (low gravity) vs.
   a snappy one.
2. **Add a platform.** Bump `NUM_RECTS` to 7 and add one column to each of
   `rect_x/y/w/h/tile` (`move.s:957`). It's automatically drawn *and* collidable.
3. **Recolour the mascot.** Edit sprite palette 0 (`move.s:950`) — e.g. `$2A` for
   a green character — and rebuild.
4. **Add a second button.** Map B (`BTN_B = $40`) to, say, a higher jump or a
   dash, in `update_player`.
5. **Redraw the character.** Edit the `IDLE/WALK1/WALK2/JUMP` grids in
   `gen_chr.py`, run `python3 assets/gen_chr.py`, rebuild.
6. **Coyote time.** Let the player jump for a few frames *after* walking off a
   ledge: keep a small countdown that's refilled while `on_ground` and allows a
   jump while non-zero. (Hint: a new zero-page byte plus a couple of lines in the
   jump check.)
7. **Add scrolling.** The big one: make the world wider than a screen and follow
   the character with `PPUSCROLL`. This needs the second nametable and feeding new
   tile columns as you move — a substantial step up, and a good next project.

---

## Further reading

- **NESdev Wiki** (<https://www.nesdev.org/wiki>) — the canonical reference for
  the CPU, PPU registers, OAM, palettes, and timing.
- **cc65 documentation** (<https://cc65.github.io/doc/>) — ca65/ld65 details.
- This repo's own emulator source (`src/main/java/com/nesemu/`) is a readable,
  modern implementation of everything described here — the PPU's
  `colourFromPalette`, the OAM DMA in `NesSystem`, and the controller shifting in
  `Controller` mirror exactly what this tutorial's ROM relies on.

[cc65 suite]: https://cc65.github.io/
