; ===========================================================================
; move-demo - a small, polished NES example: a character that walks, jumps,
; and collides with solid objects on a single screen.  No scrolling, no
; enemies - just movement that feels right and rock-solid collision.
;
; Movement model (Mario-flavoured): horizontal acceleration + friction, a
; fixed top speed, gravity, and a variable-height jump (release A early to hop
; lower).  Positions are 8.8 fixed point; collision is resolved per axis against
; a list of solid rectangles, snapping the character flush to whatever it hits.
;
; NROM (mapper 0), 32 KB PRG + 8 KB CHR.  Assemble with ca65 + ld65.
; ===========================================================================

; --- hardware registers ----------------------------------------------------
PPUCTRL=$2000
PPUMASK=$2001
PPUSTATUS=$2002
OAMADDR=$2003
PPUSCROLL=$2005
PPUADDR=$2006
PPUDATA=$2007
OAMDMA=$4014
APUSTATUS=$4015
APUFRAME=$4017
JOY1=$4016

; --- buttons (post-shift bit layout) ---------------------------------------
BTN_RIGHT=$01
BTN_LEFT =$02
BTN_A    =$80

; --- tuning (8.8 fixed: velocities are 1/256 px per frame) ------------------
WALK_ACCEL = 40
WALK_MAX   = 480           ; ~1.9 px/frame top speed
FRICTION   = 48
GRAVITY    = 44
JUMP_VEL   = 1000          ; ~3.9 px/frame initial upward
MAX_FALL   = 1200
JUMP_CUT   = 220           ; rising speed clamp when A is released

OAM = $0200

; ===========================================================================
.segment "HEADER"
    .byte "NES", $1A, $02, $01, $00, $00
    .byte 0,0,0,0,0,0,0,0

; ===========================================================================
.segment "ZEROPAGE"
nmi_ready:  .res 1
frame:      .res 1
pad:        .res 1
pad_prev:   .res 1
pad_press:  .res 1
ptr:        .res 2
tmp1:       .res 1
tmp2:       .res 1
tmp3:       .res 1
tmp4:       .res 1

px:         .res 1          ; player X (pixels)
px_sub:     .res 1
py:         .res 1          ; player Y (pixels)
py_sub:     .res 1
vx:         .res 2          ; signed 8.8 velocity
vy:         .res 2
on_ground:  .res 1
facing:     .res 1          ; 0=right 1=left
anim:       .res 1
oami:       .res 1

; collision scratch
r_x:        .res 1
r_y:        .res 1
r_w:        .res 1
r_h:        .res 1
ri:         .res 1
sign:       .res 1

.segment "BSS"
; (nothing extra needed)

; ===========================================================================
.segment "CODE"

.proc reset
    sei
    cld
    ldx #$40
    stx APUFRAME
    ldx #$FF
    txs
    inx
    stx PPUCTRL
    stx PPUMASK
    stx APUSTATUS
:   bit PPUSTATUS
    bpl :-
    lda #0
    ldx #0
:   sta $0000,x
    sta $0300,x
    sta $0400,x
    sta $0500,x
    sta $0600,x
    sta $0700,x
    inx
    bne :-
    lda #$FF
    ldx #0
:   sta OAM,x
    inx
    bne :-
:   bit PPUSTATUS
    bpl :-

    jsr load_palette
    jsr draw_scene
    jsr init_player

    lda #%10001000          ; NMI on, sprites from pattern table 1
    sta PPUCTRL
    lda #%00011110
    sta PPUMASK

loop:
    jsr read_pad
    jsr update_player
    jsr draw_player
    ; wait for NMI to present
    lda frame
:   cmp frame
    beq :-
    jmp loop
.endproc

.proc nmi
    pha
    txa
    pha
    tya
    pha
    lda #0
    sta OAMADDR
    lda #>OAM
    sta OAMDMA
    bit PPUSTATUS
    lda #0
    sta PPUSCROLL
    sta PPUSCROLL
    lda #%10001000
    sta PPUCTRL
    lda #%00011110
    sta PPUMASK
    inc frame
    pla
    tay
    pla
    tax
    pla
    rti
.endproc

.proc irq
    rti
.endproc

; ---------------------------------------------------------------------------
; input
; ---------------------------------------------------------------------------
.proc read_pad
    lda pad
    sta pad_prev
    lda #1
    sta JOY1
    lda #0
    sta JOY1
    ldx #8
    lda #0
    sta pad
:   lda JOY1
    lsr
    rol pad
    dex
    bne :-
    lda pad
    eor pad_prev
    and pad
    sta pad_press
    rts
.endproc

; ---------------------------------------------------------------------------
; player init
; ---------------------------------------------------------------------------
.proc init_player
    lda #32
    sta px
    lda #160
    sta py
    lda #0
    sta px_sub
    sta py_sub
    sta vx+0
    sta vx+1
    sta vy+0
    sta vy+1
    sta on_ground
    sta facing
    sta anim
    rts
.endproc

; ===========================================================================
; update_player - input, physics, and collision for one frame.
; ===========================================================================
.proc update_player
    ; --- horizontal input ---
    lda pad
    and #BTN_LEFT
    beq @notleft
    lda #1
    sta facing
    jsr vx_left
    jmp @vert
@notleft:
    lda pad
    and #BTN_RIGHT
    beq @noh
    lda #0
    sta facing
    jsr vx_right
    jmp @vert
@noh:
    jsr vx_friction
@vert:
    ; --- jump ---
    lda pad_press
    and #BTN_A
    beq @nojump
    lda on_ground
    beq @nojump
    lda #<(0 - JUMP_VEL)
    sta vy+0
    lda #>(0 - JUMP_VEL)
    sta vy+1
    lda #0
    sta on_ground
@nojump:
    ; --- variable jump height: cut upward speed when A released ---
    lda pad
    and #BTN_A
    bne @nocut
    lda vy+1
    bpl @nocut              ; only while rising (vy negative)
    ; if vy < -JUMP_CUT, set vy = -JUMP_CUT
    lda vy+0
    cmp #<(0 - JUMP_CUT)
    lda vy+1
    sbc #>(0 - JUMP_CUT)
    bpl @nocut              ; vy >= -JUMP_CUT already
    lda #<(0 - JUMP_CUT)
    sta vy+0
    lda #>(0 - JUMP_CUT)
    sta vy+1
@nocut:
    jsr apply_gravity
    jsr move_x
    jsr move_y
    ; animation timer
    inc anim
    rts
.endproc

; --- horizontal velocity helpers ------------------------------------------
.proc vx_right
    clc
    lda vx+0
    adc #<WALK_ACCEL
    sta vx+0
    lda vx+1
    adc #>WALK_ACCEL
    sta vx+1
    bmi @ok                 ; still negative (decelerating from a left run)
    lda #<WALK_MAX
    cmp vx+0
    lda #>WALK_MAX
    sbc vx+1
    bcs @ok
    lda #<WALK_MAX
    sta vx+0
    lda #>WALK_MAX
    sta vx+1
@ok:
    rts
.endproc

.proc vx_left
    sec
    lda vx+0
    sbc #<WALK_ACCEL
    sta vx+0
    lda vx+1
    sbc #>WALK_ACCEL
    sta vx+1
    clc
    lda vx+0
    adc #<WALK_MAX
    lda vx+1
    adc #>WALK_MAX
    bpl @ok                 ; vx >= -WALK_MAX
    lda #<(0 - WALK_MAX)
    sta vx+0
    lda #>(0 - WALK_MAX)
    sta vx+1
@ok:
    rts
.endproc

.proc vx_friction
    lda vx+1
    bmi @neg
    ; positive -> subtract, floor 0
    sec
    lda vx+0
    sbc #<FRICTION
    sta tmp1
    lda vx+1
    sbc #>FRICTION
    bmi @zero
    sta vx+1
    lda tmp1
    sta vx+0
    rts
@neg:
    clc
    lda vx+0
    adc #<FRICTION
    sta tmp1
    lda vx+1
    adc #>FRICTION
    bpl @zero
    sta vx+1
    lda tmp1
    sta vx+0
    rts
@zero:
    lda #0
    sta vx+0
    sta vx+1
    rts
.endproc

.proc apply_gravity
    clc
    lda vy+0
    adc #<GRAVITY
    sta vy+0
    lda vy+1
    adc #>GRAVITY
    sta vy+1
    bmi @ok                 ; rising (vy negative) -> never clamp to max fall
    ; clamp positive (downward) speed to MAX_FALL
    lda #<MAX_FALL
    cmp vy+0
    lda #>MAX_FALL
    sbc vy+1
    bcs @ok
    lda #<MAX_FALL
    sta vy+0
    lda #>MAX_FALL
    sta vy+1
@ok:
    rts
.endproc

; ===========================================================================
; Collision.  The player hitbox is x+2..x+13 (w 11), y+1..y+15 (h 14).
; Move one axis, then push out of any solid rectangle it now overlaps.
; ===========================================================================
HB_L = 2
HB_R = 13
HB_T = 1
HB_B = 15

.proc move_x
    ; px:px_sub += vx (signed)
    lda #0
    ldx vx+1
    bpl :+
    lda #$FF
:   sta sign
    clc
    lda px_sub
    adc vx+0
    sta px_sub
    lda px
    adc vx+1
    sta px
    ; (sign byte only matters if px is 16-bit; px is 8-bit, clamp below)
    ; clamp to screen [0, 240].  px is unsigned, so distinguish an overshoot
    ; past the right edge from an underflow past the left edge by the travel
    ; direction (sign was set from vx's high byte above).  Max step is ~2 px,
    ; so any value in 241..255 is one or the other, never a full wrap.
    lda px
    cmp #241
    bcc @rects              ; 0..240 -> in range
    lda sign
    beq @clamphi            ; sign 0 = moving right -> clamp to 240
    lda #0                  ; moving left underflowed -> clamp to 0
    sta px
    sta px_sub
    jmp @rects
@clamphi:
    lda #240
    sta px
@rects:
    ldx #0
@loop:
    stx ri
    jsr load_rect
    jsr overlap
    bcc @next
    ; overlap: push horizontally based on direction of travel
    lda vx+1
    bmi @hitleft
    ; moving right -> place right edge flush to rect left
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
    sbc #HB_L
    sta px
@stop:
    lda #0
    sta px_sub
    sta vx+0
    sta vx+1
@next:
    ldx ri
    inx
    cpx #NUM_RECTS
    bne @loop
    rts
.endproc

.proc move_y
    clc
    lda py_sub
    adc vy+0
    sta py_sub
    lda py
    adc vy+1
    sta py
    ldx #0
@loop:
    stx ri
    jsr load_rect
    jsr overlap
    bcc @next
    lda vy+1
    bmi @hitup
    ; moving down -> land on top of rect
    lda r_y
    sec
    sbc #(HB_B+1)
    sta py
    jmp @stop
@hitup:
    lda r_y
    clc
    adc r_h
    sec
    sbc #HB_T
    sta py
@stop:
    lda #0
    sta py_sub
    sta vy+0
    sta vy+1
@next:
    ldx ri
    inx
    cpx #NUM_RECTS
    bne @loop
    ; grounded state is decided by a separate 1px-down probe so that resting
    ; on a surface reads as grounded every frame (the move overlap alone would
    ; only trip intermittently as the sub-pixel accumulator rolls over).
    jsr ground_probe
    rts
.endproc

; ground_probe - set on_ground = 1 if a solid sits within 1px below the feet.
.proc ground_probe
    lda #0
    sta on_ground
    inc py                  ; nudge down one pixel
    ldx #0
@loop:
    stx ri
    jsr load_rect
    jsr overlap
    bcc @next
    lda #1
    sta on_ground
@next:
    ldx ri
    inx
    cpx #NUM_RECTS
    bne @loop
    dec py                  ; restore
    rts
.endproc

; load_rect - load rectangle ri into r_x/r_y/r_w/r_h.
.proc load_rect
    ldx ri
    lda rect_x, x
    sta r_x
    lda rect_y, x
    sta r_y
    lda rect_w, x
    sta r_w
    lda rect_h, x
    sta r_h
    rts
.endproc

; overlap - carry set if player hitbox overlaps rectangle r_*.
;   player box: [px+HB_L .. px+HB_R] x [py+HB_T .. py+HB_B]
;   rect box:   [r_x .. r_x+r_w-1]   x [r_y .. r_y+r_h-1]
.proc overlap
    ; player_left (px+HB_L) > rect_right (r_x+r_w-1)? -> no overlap
    lda r_x
    clc
    adc r_w                 ; r_x + r_w  (= right edge + 1)
    sta tmp1
    lda px
    clc
    adc #HB_L               ; player_left
    cmp tmp1                ; player_left >= r_x+r_w ?
    bcs @no
    ; rect_left (r_x) > player_right (px+HB_R)? -> no
    lda px
    clc
    adc #HB_R
    sta tmp2                ; player_right
    lda r_x
    cmp tmp2
    beq @yx
    bcs @no                 ; r_x > player_right
@yx:
    ; vertical
    lda r_y
    clc
    adc r_h
    sta tmp1                ; r_y + r_h
    lda py
    clc
    adc #HB_T
    cmp tmp1                ; player_top >= r_y+r_h ?
    bcs @no
    lda py
    clc
    adc #HB_B
    sta tmp2                ; player_bottom
    lda r_y
    cmp tmp2
    beq @yes
    bcs @no
@yes:
    sec
    rts
@no:
    clc
    rts
.endproc

; ===========================================================================
; Drawing
; ===========================================================================
.proc draw_player
    lda #0
    sta oami
    ; choose 16x16 tile base
    lda on_ground
    bne @grounded
    lda #$0C                ; jump
    jmp @base
@grounded:
    lda vx+0
    ora vx+1
    beq @idle
    lda anim
    and #$08
    beq @w1
    lda #$08
    jmp @base
@w1:
    lda #$04
    jmp @base
@idle:
    lda #$00
@base:
    sta tmp3                ; base tile
    lda facing
    bne @flip

    ; --- right-facing quad TL,TR,BL,BR ---
    ldx #0
    lda py
    sta tmp1
    lda tmp3
    sta tmp2
    lda px
    sta tmp4
    jsr put_sprite          ; TL
    lda py
    sta tmp1
    lda tmp3
    clc
    adc #1
    sta tmp2
    lda px
    clc
    adc #8
    sta tmp4
    jsr put_sprite          ; TR
    lda py
    clc
    adc #8
    sta tmp1
    lda tmp3
    clc
    adc #2
    sta tmp2
    lda px
    sta tmp4
    jsr put_sprite          ; BL
    lda py
    clc
    adc #8
    sta tmp1
    lda tmp3
    clc
    adc #3
    sta tmp2
    lda px
    clc
    adc #8
    sta tmp4
    jsr put_sprite          ; BR
    rts
@flip:
    ; mirrored: swap left/right columns and set flip bit ($40 via put_sprite_f)
    lda py
    sta tmp1
    lda tmp3
    clc
    adc #1
    sta tmp2
    lda px
    sta tmp4
    jsr put_sprite_f
    lda py
    sta tmp1
    lda tmp3
    sta tmp2
    lda px
    clc
    adc #8
    sta tmp4
    jsr put_sprite_f
    lda py
    clc
    adc #8
    sta tmp1
    lda tmp3
    clc
    adc #3
    sta tmp2
    lda px
    sta tmp4
    jsr put_sprite_f
    lda py
    clc
    adc #8
    sta tmp1
    lda tmp3
    clc
    adc #2
    sta tmp2
    lda px
    clc
    adc #8
    sta tmp4
    jsr put_sprite_f
    rts
.endproc

; put_sprite - write one OAM entry: Y=tmp1, tile=tmp2, attr=0, X=tmp4.
.proc put_sprite
    ldx oami
    lda tmp1
    sta OAM+0, x
    lda tmp2
    sta OAM+1, x
    lda #0
    sta OAM+2, x
    lda tmp4
    sta OAM+3, x
    txa
    clc
    adc #4
    sta oami
    rts
.endproc

; put_sprite_f - same but with horizontal-flip attribute.
.proc put_sprite_f
    ldx oami
    lda tmp1
    sta OAM+0, x
    lda tmp2
    sta OAM+1, x
    lda #$40
    sta OAM+2, x
    lda tmp4
    sta OAM+3, x
    txa
    clc
    adc #4
    sta oami
    rts
.endproc

; ---------------------------------------------------------------------------
; palette + scene setup (rendering disabled)
; ---------------------------------------------------------------------------
.proc load_palette
    bit PPUSTATUS
    lda #$3F
    sta PPUADDR
    lda #$00
    sta PPUADDR
    ldx #0
:   lda palette, x
    sta PPUDATA
    inx
    cpx #32
    bne :-
    rts
.endproc

; draw_scene - clear to sky, draw clouds, then stamp each solid rectangle.
.proc draw_scene
    ; clear nametable 0 to sky tile $00
    bit PPUSTATUS
    lda #$20
    sta PPUADDR
    lda #$00
    sta PPUADDR
    lda #$00
    ldx #4
    ldy #0
:   sta PPUDATA
    iny
    bne :-
    dex
    bne :-
    ; clear attributes to palette 0
    bit PPUSTATUS
    lda #$23
    sta PPUADDR
    lda #$C0
    sta PPUADDR
    lda #$00
    ldy #64
:   sta PPUDATA
    dey
    bne :-
    ; top attribute row -> palette 1 so cloud tiles (colour 1) appear white
    bit PPUSTATUS
    lda #$23
    sta PPUADDR
    lda #$C0
    sta PPUADDR
    lda #$55                ; all four quadrants = palette 1
    ldy #8
:   sta PPUDATA
    dey
    bne :-

    jsr draw_clouds

    ; draw every solid rectangle as tiles
    ldx #0
@loop:
    stx ri
    jsr load_rect
    ldx ri
    lda rect_tile, x
    sta tmp3                ; tile to use
    jsr fill_rect_tiles
    ldx ri
    inx
    cpx #NUM_RECTS
    bne @loop

    ; lay a grass strip along the ground top row (rect 0 is the ground)
    jsr draw_grass_line
    rts
.endproc

; fill_rect_tiles - fill the nametable cells covered by r_* with tile tmp3.
;   tile coords: tx=r_x/8, ty=r_y/8, tw=ceil(r_w/8), th=ceil(r_h/8).
.proc fill_rect_tiles
    lda r_y
    lsr
    lsr
    lsr
    sta tmp1                ; ty
    lda r_h
    clc
    adc #7
    lsr
    lsr
    lsr
    sta tmp2                ; th (rows remaining)
@row:
    ; set PPUADDR = $2000 + ty*32 + tx
    lda r_x
    lsr
    lsr
    lsr
    sta tmp4                ; tx
    lda tmp1                ; ty
    and #$07
    asl
    asl
    asl
    asl
    asl                     ; (ty&7)*32
    ora tmp4                ; + tx
    sta ptr+0
    lda tmp1
    lsr
    lsr
    lsr                     ; ty>>3
    clc
    adc #$20
    sta ptr+1
    bit PPUSTATUS
    lda ptr+1
    sta PPUADDR
    lda ptr+0
    sta PPUADDR
    ; width in tiles
    lda r_w
    clc
    adc #7
    lsr
    lsr
    lsr
    tay                     ; tw
    lda tmp3
:   sta PPUDATA
    dey
    bne :-
    inc tmp1                ; next ty
    dec tmp2
    bne @row
    rts
.endproc

; draw_grass_line - overwrite the ground's top tile row with grass ($01).
.proc draw_grass_line
    lda rect_y+0            ; ground rect y
    lsr
    lsr
    lsr
    sta tmp1                ; ty
    lda tmp1
    and #$07
    asl
    asl
    asl
    asl
    asl
    sta ptr+0              ; tx=0
    lda tmp1
    lsr
    lsr
    lsr
    clc
    adc #$20
    sta ptr+1
    bit PPUSTATUS
    lda ptr+1
    sta PPUADDR
    lda ptr+0
    sta PPUADDR
    ldy #32
    lda #$01
:   sta PPUDATA
    dey
    bne :-
    rts
.endproc

; draw_clouds - a few decorative cloud tiles in the sky.
.proc draw_clouds
    ldx #0
@loop:
    lda cloud_addr_hi, x
    sta tmp1
    lda cloud_addr_lo, x
    sta tmp2
    bit PPUSTATUS
    lda tmp1
    sta PPUADDR
    lda tmp2
    sta PPUADDR
    lda #$05
    sta PPUDATA
    sta PPUDATA            ; two-tile puff
    inx
    cpx #4
    bne @loop
    rts
.endproc

; ===========================================================================
; Data
; ===========================================================================
.segment "RODATA"
palette:
    ; background
    .byte $22, $1A, $17, $0F   ; 0: sky, grass green, dirt brown, black
    .byte $22, $30, $30, $30   ; 1: clouds (white) - used by the top sky rows
    .byte $22, $21, $2C, $30
    .byte $22, $30, $16, $27
    ; sprites
    .byte $22, $27, $30, $0F   ; 0: mascot - orange body, white, black outline
    .byte $22, $16, $30, $0F
    .byte $22, $2A, $30, $0F
    .byte $22, $11, $30, $0F

; Solid rectangles (pixels).  Rect 0 must be the ground (used for the grass
; strip).  x,y,w,h are parallel arrays; rect_tile is the fill tile.
NUM_RECTS = 6
;            ground  platA  platB  platC  pillar  block
rect_x:    .byte   0,   48,  144,   96,  208,   24
rect_y:    .byte 208,  176,  144,  112,  160,  140
rect_w:    .byte 240,   64,   48,   32,   16,   16
rect_h:    .byte  32,    8,    8,    8,   56,   16
rect_tile: .byte $02,  $04,  $04,  $04,  $03,  $03

cloud_addr_hi: .byte $20, $20, $20, $20
cloud_addr_lo: .byte $43, $4D, $58, $62

; ===========================================================================
.segment "VECTORS"
    .addr nmi, reset, irq

.segment "CHARS"
    .incbin "../assets/chr.bin"
