package com.nesemu.ppu;

import com.nesemu.cartridge.Cartridge;
import com.nesemu.cartridge.Mirroring;

/**
 * Emulates the NES Picture Processing Unit (2C02) closely enough to render
 * commercial NROM games such as Super Mario Bros.
 *
 * <p>It ticks once per PPU "dot". The NES runs the PPU three times for every CPU
 * cycle, producing a 341-dot by 262-scanline frame. This implementation follows
 * the standard cycle-accurate background pipeline (the "loopy" VRAM address
 * registers, background shift registers and per-dot fetches) together with
 * per-scanline sprite evaluation and sprite-zero hit detection. That accuracy is
 * what lets the SMB status bar stay fixed while the play-field scrolls.</p>
 */
public final class Ppu {

    // --- PPUCTRL ($2000) bits ---
    private static final int CTRL_NAMETABLE_X   = 0x01;
    private static final int CTRL_NAMETABLE_Y   = 0x02;
    private static final int CTRL_INCREMENT     = 0x04;
    private static final int CTRL_PATTERN_SPRITE = 0x08;
    private static final int CTRL_PATTERN_BG    = 0x10;
    private static final int CTRL_SPRITE_SIZE   = 0x20;
    private static final int CTRL_NMI           = 0x80;

    // --- PPUMASK ($2001) bits ---
    private static final int MASK_BG_LEFT  = 0x02;
    private static final int MASK_SPR_LEFT = 0x04;
    private static final int MASK_BG       = 0x08;
    private static final int MASK_SPR      = 0x10;

    // --- PPUSTATUS ($2002) bits ---
    private static final int STATUS_OVERFLOW = 0x20;
    private static final int STATUS_ZERO_HIT = 0x40;
    private static final int STATUS_VBLANK   = 0x80;

    /** The 64-colour NES master palette as ARGB values. */
    private static final int[] NES_PALETTE = buildNesPalette();

    private Cartridge cartridge;

    // PPU memory
    private final byte[][] nametable = new byte[2][1024]; // 2 KB on-board VRAM
    private final byte[] paletteRam = new byte[32];
    private final byte[] oam = new byte[256]; // 64 sprites * 4 bytes

    // Registers
    private int control;
    private int mask;
    private int statusReg;
    private int oamAddr;

    // Loopy scroll registers (15-bit). See nesdev "PPU scrolling".
    private int vramAddr; // current VRAM address ("v")
    private int tramAddr; // temporary VRAM address ("t")
    private int fineX;    // fine X scroll (3 bits)
    private boolean addressLatch; // first/second write toggle ("w")
    private int dataBuffer;       // buffered PPUDATA read

    // Background pipeline
    private int bgNextTileId;
    private int bgNextTileAttrib;
    private int bgNextTileLsb;
    private int bgNextTileMsb;
    private int bgShifterPatternLo;
    private int bgShifterPatternHi;
    private int bgShifterAttribLo;
    private int bgShifterAttribHi;

    // Sprite pipeline (secondary OAM holds up to 8 sprites for the scanline)
    private final int[] spriteScanline = new int[8 * 4];
    private int spriteCount;
    private final int[] spriteShifterLo = new int[8];
    private final int[] spriteShifterHi = new int[8];
    private boolean spriteZeroHitPossible;
    private boolean spriteZeroBeingRendered;

    // Timing
    private int scanline; // -1 (pre-render) .. 260
    private int cycle;    // 0 .. 340
    private boolean oddFrame;

    // Outputs
    private boolean nmiRequest;
    private boolean frameComplete;
    private final int[] framebuffer = new int[256 * 240];

    public void connectCartridge(Cartridge cartridge) {
        this.cartridge = cartridge;
    }

    // ------------------------------------------------------------------
    // CPU-facing register interface ($2000-$2007, mirrored to $3FFF)
    // ------------------------------------------------------------------

    public int cpuRead(int addr) {
        int reg = addr & 0x0007;
        int data = 0;
        switch (reg) {
            case 0x0002 -> { // PPUSTATUS
                // Top 3 bits are the status flags; the rest is stale data-buffer noise.
                data = (statusReg & 0xE0) | (dataBuffer & 0x1F);
                // Reading the status register clears VBlank and the write latch.
                statusReg &= ~STATUS_VBLANK;
                addressLatch = false;
            }
            case 0x0004 -> data = oam[oamAddr] & 0xFF; // OAMDATA
            case 0x0007 -> { // PPUDATA
                // Reads are delayed by one fetch except for palette memory.
                data = dataBuffer;
                dataBuffer = ppuRead(vramAddr);
                if (vramAddr >= 0x3F00) {
                    data = dataBuffer;
                }
                incrementVramAddress();
            }
            default -> { /* $2000, $2001, $2003, $2005, $2006 are write-only */ }
        }
        return data & 0xFF;
    }

    public void cpuWrite(int addr, int value) {
        int reg = addr & 0x0007;
        value &= 0xFF;
        switch (reg) {
            case 0x0000 -> { // PPUCTRL
                control = value;
                // Update temp nametable select bits.
                tramAddr = (tramAddr & ~0x0C00)
                        | ((control & CTRL_NAMETABLE_X) != 0 ? 0x0400 : 0)
                        | ((control & CTRL_NAMETABLE_Y) != 0 ? 0x0800 : 0);
            }
            case 0x0001 -> mask = value; // PPUMASK
            case 0x0003 -> oamAddr = value; // OAMADDR
            case 0x0004 -> { // OAMDATA
                oam[oamAddr] = (byte) value;
                oamAddr = (oamAddr + 1) & 0xFF;
            }
            case 0x0005 -> { // PPUSCROLL
                if (!addressLatch) {
                    fineX = value & 0x07;
                    tramAddr = (tramAddr & ~0x001F) | (value >> 3);
                    addressLatch = true;
                } else {
                    tramAddr = (tramAddr & ~0x73E0)
                            | ((value & 0x07) << 12)        // fine Y
                            | ((value & 0xF8) << 2);        // coarse Y
                    addressLatch = false;
                }
            }
            case 0x0006 -> { // PPUADDR
                if (!addressLatch) {
                    tramAddr = (tramAddr & 0x00FF) | ((value & 0x3F) << 8);
                    addressLatch = true;
                } else {
                    tramAddr = (tramAddr & 0xFF00) | value;
                    vramAddr = tramAddr;
                    addressLatch = false;
                }
            }
            case 0x0007 -> { // PPUDATA
                ppuWrite(vramAddr, value);
                incrementVramAddress();
            }
            default -> { }
        }
    }

    private void incrementVramAddress() {
        int step = (control & CTRL_INCREMENT) != 0 ? 32 : 1;
        vramAddr = (vramAddr + step) & 0x7FFF;
    }

    /** Write a byte into OAM during $4014 DMA, advancing OAMADDR like hardware. */
    public void oamDmaWrite(int value) {
        oam[oamAddr] = (byte) (value & 0xFF);
        oamAddr = (oamAddr + 1) & 0xFF;
    }

    // ------------------------------------------------------------------
    // PPU bus ($0000-$3FFF): pattern tables, nametables, palettes
    // ------------------------------------------------------------------

    private int ppuRead(int addr) {
        addr &= 0x3FFF;
        if (addr <= 0x1FFF) {
            // Pattern memory comes from the cartridge (CHR ROM/RAM).
            return cartridge.ppuRead(addr) & 0xFF;
        } else if (addr <= 0x3EFF) {
            return readNametable(addr) & 0xFF;
        } else {
            return readPalette(addr) & 0xFF;
        }
    }

    private void ppuWrite(int addr, int value) {
        addr &= 0x3FFF;
        value &= 0xFF;
        if (addr <= 0x1FFF) {
            cartridge.ppuWrite(addr, value);
        } else if (addr <= 0x3EFF) {
            writeNametable(addr, value);
        } else {
            writePalette(addr, value);
        }
    }

    private int nametableIndex(int addr) {
        // addr is within $2000-$3EFF; reduce to a 0..0x0FFF nametable offset.
        int a = addr & 0x0FFF;
        Mirroring m = cartridge.getMirroring();
        int quadrant = a / 0x0400; // 0..3
        return switch (m) {
            case VERTICAL -> (quadrant == 0 || quadrant == 2) ? 0 : 1;
            case HORIZONTAL -> (quadrant == 0 || quadrant == 1) ? 0 : 1;
            case SINGLE_SCREEN_LOWER -> 0;
            case SINGLE_SCREEN_UPPER -> 1;
            case FOUR_SCREEN -> quadrant & 1; // approximation (no extra VRAM here)
        };
    }

    private int readNametable(int addr) {
        int table = nametableIndex(addr);
        return nametable[table][addr & 0x03FF] & 0xFF;
    }

    private void writeNametable(int addr, int value) {
        int table = nametableIndex(addr);
        nametable[table][addr & 0x03FF] = (byte) value;
    }

    private int paletteIndex(int addr) {
        int a = addr & 0x001F;
        // $3F10/$3F14/$3F18/$3F1C mirror $3F00/$3F04/$3F08/$3F0C.
        if (a == 0x10) a = 0x00;
        else if (a == 0x14) a = 0x04;
        else if (a == 0x18) a = 0x08;
        else if (a == 0x1C) a = 0x0C;
        return a;
    }

    private int readPalette(int addr) {
        return paletteRam[paletteIndex(addr)] & 0x3F;
    }

    private void writePalette(int addr, int value) {
        paletteRam[paletteIndex(addr)] = (byte) (value & 0x3F);
    }

    /** Look up the ARGB colour for a (palette, pixel) pair. */
    private int colourFromPalette(int palette, int pixel) {
        int index = ppuRead(0x3F00 + (palette << 2) + pixel) & 0x3F;
        return NES_PALETTE[index];
    }

    // ------------------------------------------------------------------
    // Loopy address helpers
    // ------------------------------------------------------------------

    private boolean renderingEnabled() {
        return (mask & (MASK_BG | MASK_SPR)) != 0;
    }

    private void incrementScrollX() {
        if (!renderingEnabled()) return;
        if ((vramAddr & 0x001F) == 31) {       // coarse X == 31
            vramAddr &= ~0x001F;                // coarse X = 0
            vramAddr ^= 0x0400;                 // switch horizontal nametable
        } else {
            vramAddr += 1;
        }
    }

    private void incrementScrollY() {
        if (!renderingEnabled()) return;
        if ((vramAddr & 0x7000) != 0x7000) {   // fine Y < 7
            vramAddr += 0x1000;
        } else {
            vramAddr &= ~0x7000;               // fine Y = 0
            int coarseY = (vramAddr & 0x03E0) >> 5;
            if (coarseY == 29) {
                coarseY = 0;
                vramAddr ^= 0x0800;            // switch vertical nametable
            } else if (coarseY == 31) {
                coarseY = 0;                  // out-of-bounds, just wrap
            } else {
                coarseY += 1;
            }
            vramAddr = (vramAddr & ~0x03E0) | (coarseY << 5);
        }
    }

    private void transferAddressX() {
        if (!renderingEnabled()) return;
        vramAddr = (vramAddr & ~0x041F) | (tramAddr & 0x041F);
    }

    private void transferAddressY() {
        if (!renderingEnabled()) return;
        vramAddr = (vramAddr & ~0x7BE0) | (tramAddr & 0x7BE0);
    }

    private void loadBackgroundShifters() {
        bgShifterPatternLo = (bgShifterPatternLo & 0xFF00) | bgNextTileLsb;
        bgShifterPatternHi = (bgShifterPatternHi & 0xFF00) | bgNextTileMsb;
        bgShifterAttribLo = (bgShifterAttribLo & 0xFF00) | ((bgNextTileAttrib & 0b01) != 0 ? 0xFF : 0x00);
        bgShifterAttribHi = (bgShifterAttribHi & 0xFF00) | ((bgNextTileAttrib & 0b10) != 0 ? 0xFF : 0x00);
    }

    private void updateShifters() {
        if ((mask & MASK_BG) != 0) {
            bgShifterPatternLo = (bgShifterPatternLo << 1) & 0xFFFF;
            bgShifterPatternHi = (bgShifterPatternHi << 1) & 0xFFFF;
            bgShifterAttribLo = (bgShifterAttribLo << 1) & 0xFFFF;
            bgShifterAttribHi = (bgShifterAttribHi << 1) & 0xFFFF;
        }
        if ((mask & MASK_SPR) != 0 && cycle >= 1 && cycle < 258) {
            for (int i = 0; i < spriteCount; i++) {
                if (spriteScanline[i * 4 + 3] > 0) {
                    spriteScanline[i * 4 + 3]--; // decrement X position
                } else {
                    spriteShifterLo[i] = (spriteShifterLo[i] << 1) & 0xFF;
                    spriteShifterHi[i] = (spriteShifterHi[i] << 1) & 0xFF;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Main clock - one PPU dot
    // ------------------------------------------------------------------

    public void clock() {
        if (scanline >= -1 && scanline < 240) {
            // -- Visible & pre-render scanlines --------------------------
            if (scanline == 0 && cycle == 0 && oddFrame && renderingEnabled()) {
                cycle = 1; // skip an idle cycle on odd frames
            }

            if (scanline == -1 && cycle == 1) {
                // Start of a new frame: clear VBlank, sprite-zero hit, overflow.
                statusReg &= ~STATUS_VBLANK;
                statusReg &= ~STATUS_ZERO_HIT;
                statusReg &= ~STATUS_OVERFLOW;
                for (int i = 0; i < 8; i++) {
                    spriteShifterLo[i] = 0;
                    spriteShifterHi[i] = 0;
                }
            }

            if ((cycle >= 2 && cycle < 258) || (cycle >= 321 && cycle < 338)) {
                updateShifters();

                switch ((cycle - 1) % 8) {
                    case 0 -> {
                        loadBackgroundShifters();
                        bgNextTileId = ppuRead(0x2000 | (vramAddr & 0x0FFF));
                    }
                    case 2 -> {
                        int attribAddr = 0x23C0
                                | (vramAddr & 0x0C00)
                                | ((vramAddr >> 4) & 0x38)
                                | ((vramAddr >> 2) & 0x07);
                        bgNextTileAttrib = ppuRead(attribAddr);
                        if ((((vramAddr & 0x03E0) >> 5) & 0x02) != 0) bgNextTileAttrib >>= 4;
                        if ((vramAddr & 0x02) != 0) bgNextTileAttrib >>= 2;
                        bgNextTileAttrib &= 0x03;
                    }
                    case 4 -> {
                        int patternBase = ((control & CTRL_PATTERN_BG) != 0 ? 0x1000 : 0x0000);
                        int fineY = (vramAddr >> 12) & 0x07;
                        bgNextTileLsb = ppuRead(patternBase + (bgNextTileId << 4) + fineY);
                    }
                    case 6 -> {
                        int patternBase = ((control & CTRL_PATTERN_BG) != 0 ? 0x1000 : 0x0000);
                        int fineY = (vramAddr >> 12) & 0x07;
                        bgNextTileMsb = ppuRead(patternBase + (bgNextTileId << 4) + fineY + 8);
                    }
                    case 7 -> incrementScrollX();
                    default -> { }
                }
            }

            if (cycle == 256) {
                incrementScrollY();
            }
            if (cycle == 257) {
                loadBackgroundShifters();
                transferAddressX();
            }
            if (cycle == 338 || cycle == 340) {
                bgNextTileId = ppuRead(0x2000 | (vramAddr & 0x0FFF));
            }
            if (scanline == -1 && cycle >= 280 && cycle < 305) {
                transferAddressY();
            }

            // -- Sprite evaluation for the NEXT scanline -----------------
            if (cycle == 257 && scanline >= 0) {
                evaluateSprites();
            }
            if (cycle == 340 && scanline >= -1) {
                loadSpriteShifters();
            }

            // Drive the MMC3-style scanline IRQ counter. On hardware this is
            // clocked by PPU address line A12 toggling during the fetch pipeline;
            // clocking once per rendered scanline (here, after the visible dots)
            // is accurate enough for the split-screen IRQs games rely on.
            if (cycle == 260 && renderingEnabled() && cartridge != null) {
                cartridge.clockScanlineCounter();
            }
        }

        if (scanline == 240) {
            // Post-render scanline: PPU is idle.
        }

        if (scanline == 241 && cycle == 1) {
            // Enter VBlank and optionally fire NMI.
            statusReg |= STATUS_VBLANK;
            if ((control & CTRL_NMI) != 0) {
                nmiRequest = true;
            }
        }

        // -- Compose the output pixel ------------------------------------
        composePixel();

        // -- Advance counters --------------------------------------------
        cycle++;
        if (cycle >= 341) {
            cycle = 0;
            scanline++;
            if (scanline >= 261) {
                scanline = -1;
                frameComplete = true;
                oddFrame = !oddFrame;
            }
        }
    }

    private void composePixel() {
        int bgPixel = 0;
        int bgPalette = 0;
        if ((mask & MASK_BG) != 0) {
            if ((mask & MASK_BG_LEFT) != 0 || cycle >= 9) {
                int bitMux = 0x8000 >> fineX;
                int p0 = (bgShifterPatternLo & bitMux) != 0 ? 1 : 0;
                int p1 = (bgShifterPatternHi & bitMux) != 0 ? 1 : 0;
                bgPixel = (p1 << 1) | p0;
                int pal0 = (bgShifterAttribLo & bitMux) != 0 ? 1 : 0;
                int pal1 = (bgShifterAttribHi & bitMux) != 0 ? 1 : 0;
                bgPalette = (pal1 << 1) | pal0;
            }
        }

        int fgPixel = 0;
        int fgPalette = 0;
        boolean fgPriority = false;
        if ((mask & MASK_SPR) != 0) {
            if ((mask & MASK_SPR_LEFT) != 0 || cycle >= 9) {
                spriteZeroBeingRendered = false;
                for (int i = 0; i < spriteCount; i++) {
                    if (spriteScanline[i * 4 + 3] == 0) { // X position reached
                        int lo = (spriteShifterLo[i] & 0x80) != 0 ? 1 : 0;
                        int hi = (spriteShifterHi[i] & 0x80) != 0 ? 1 : 0;
                        fgPixel = (hi << 1) | lo;
                        int attrib = spriteScanline[i * 4 + 2];
                        fgPalette = (attrib & 0x03) + 0x04;
                        fgPriority = (attrib & 0x20) == 0;
                        if (fgPixel != 0) {
                            if (i == 0) {
                                spriteZeroBeingRendered = true;
                            }
                            break; // highest priority opaque sprite wins
                        }
                    }
                }
            }
        }

        // -- Priority multiplexer between background and sprite ----------
        int pixel = 0;
        int palette = 0;
        if (bgPixel == 0 && fgPixel == 0) {
            pixel = 0;
            palette = 0;
        } else if (bgPixel == 0 && fgPixel > 0) {
            pixel = fgPixel;
            palette = fgPalette;
        } else if (bgPixel > 0 && fgPixel == 0) {
            pixel = bgPixel;
            palette = bgPalette;
        } else {
            // Both opaque: priority bit decides, and sprite zero may flag a hit.
            if (fgPriority) {
                pixel = fgPixel;
                palette = fgPalette;
            } else {
                pixel = bgPixel;
                palette = bgPalette;
            }
            if (spriteZeroHitPossible && spriteZeroBeingRendered
                    && (mask & MASK_BG) != 0 && (mask & MASK_SPR) != 0) {
                int left = ((mask & (MASK_BG_LEFT | MASK_SPR_LEFT)) != (MASK_BG_LEFT | MASK_SPR_LEFT)) ? 9 : 1;
                if (cycle >= left && cycle < 258) {
                    statusReg |= STATUS_ZERO_HIT;
                }
            }
        }

        // Plot the pixel if we are within the visible region.
        int x = cycle - 1;
        int y = scanline;
        if (x >= 0 && x < 256 && y >= 0 && y < 240) {
            framebuffer[y * 256 + x] = colourFromPalette(palette, pixel);
        }
    }

    // ------------------------------------------------------------------
    // Sprite evaluation
    // ------------------------------------------------------------------

    private void evaluateSprites() {
        // Clear secondary OAM.
        for (int i = 0; i < spriteScanline.length; i++) {
            spriteScanline[i] = 0xFF;
        }
        spriteCount = 0;
        spriteZeroHitPossible = false;

        int spriteHeight = (control & CTRL_SPRITE_SIZE) != 0 ? 16 : 8;
        int oamEntry = 0;
        while (oamEntry < 64 && spriteCount < 9) {
            int spriteY = oam[oamEntry * 4] & 0xFF;
            int diff = scanline - spriteY;
            if (diff >= 0 && diff < spriteHeight) {
                if (spriteCount < 8) {
                    if (oamEntry == 0) {
                        spriteZeroHitPossible = true;
                    }
                    spriteScanline[spriteCount * 4]     = spriteY;
                    spriteScanline[spriteCount * 4 + 1] = oam[oamEntry * 4 + 1] & 0xFF; // tile id
                    spriteScanline[spriteCount * 4 + 2] = oam[oamEntry * 4 + 2] & 0xFF; // attributes
                    spriteScanline[spriteCount * 4 + 3] = oam[oamEntry * 4 + 3] & 0xFF; // x
                    spriteCount++;
                } else {
                    statusReg |= STATUS_OVERFLOW;
                }
            }
            oamEntry++;
        }
    }

    private void loadSpriteShifters() {
        int spriteHeight = (control & CTRL_SPRITE_SIZE) != 0 ? 16 : 8;
        for (int i = 0; i < spriteCount; i++) {
            int spriteY = spriteScanline[i * 4];
            int tileId = spriteScanline[i * 4 + 1];
            int attrib = spriteScanline[i * 4 + 2];

            boolean flipV = (attrib & 0x80) != 0;
            boolean flipH = (attrib & 0x40) != 0;

            int row = scanline - spriteY;

            int addr;
            if (spriteHeight == 8) {
                int patternBase = (control & CTRL_PATTERN_SPRITE) != 0 ? 0x1000 : 0x0000;
                int fineRow = flipV ? (7 - row) : row;
                addr = patternBase + (tileId << 4) + (fineRow & 0x07);
            } else {
                // 8x16 sprites: bit 0 of tile id picks the pattern table.
                int patternBase = (tileId & 0x01) != 0 ? 0x1000 : 0x0000;
                int tile = tileId & 0xFE;
                int fineRow = flipV ? (15 - row) : row;
                if (fineRow >= 8) {
                    tile += 1;
                    fineRow -= 8;
                }
                addr = patternBase + (tile << 4) + (fineRow & 0x07);
            }

            int lo = ppuRead(addr);
            int hi = ppuRead(addr + 8);

            if (flipH) {
                lo = reverseByte(lo);
                hi = reverseByte(hi);
            }

            spriteShifterLo[i] = lo & 0xFF;
            spriteShifterHi[i] = hi & 0xFF;
        }
    }

    private static int reverseByte(int b) {
        b = (b & 0xF0) >> 4 | (b & 0x0F) << 4;
        b = (b & 0xCC) >> 2 | (b & 0x33) << 2;
        b = (b & 0xAA) >> 1 | (b & 0x55) << 1;
        return b & 0xFF;
    }

    // ------------------------------------------------------------------
    // System interface
    // ------------------------------------------------------------------

    public boolean isFrameComplete() {
        return frameComplete;
    }

    public void clearFrameComplete() {
        frameComplete = false;
    }

    public boolean isNmiRequested() {
        return nmiRequest;
    }

    public void clearNmiRequest() {
        nmiRequest = false;
    }

    public int[] getFramebuffer() {
        return framebuffer;
    }

    public void reset() {
        control = 0;
        mask = 0;
        statusReg = 0;
        oamAddr = 0;
        vramAddr = 0;
        tramAddr = 0;
        fineX = 0;
        addressLatch = false;
        dataBuffer = 0;
        scanline = 0;
        cycle = 0;
        oddFrame = false;
        nmiRequest = false;
        frameComplete = false;
        bgNextTileId = 0;
        bgNextTileAttrib = 0;
        bgNextTileLsb = 0;
        bgNextTileMsb = 0;
        bgShifterPatternLo = 0;
        bgShifterPatternHi = 0;
        bgShifterAttribLo = 0;
        bgShifterAttribHi = 0;
        spriteCount = 0;
    }

    // ------------------------------------------------------------------
    // NES master palette
    // ------------------------------------------------------------------

    private static int[] buildNesPalette() {
        // Standard 2C02 palette (one common, widely-used set of RGB values).
        int[][] rgb = {
            {84, 84, 84},    {0, 30, 116},    {8, 16, 144},    {48, 0, 136},
            {68, 0, 100},    {92, 0, 48},     {84, 4, 0},      {60, 24, 0},
            {32, 42, 0},     {8, 58, 0},      {0, 64, 0},      {0, 60, 0},
            {0, 50, 60},     {0, 0, 0},       {0, 0, 0},       {0, 0, 0},
            {152, 150, 152}, {8, 76, 196},    {48, 50, 236},   {92, 30, 228},
            {136, 20, 176},  {160, 20, 100},  {152, 34, 32},   {120, 60, 0},
            {84, 90, 0},     {40, 114, 0},    {8, 124, 0},     {0, 118, 40},
            {0, 102, 120},   {0, 0, 0},       {0, 0, 0},       {0, 0, 0},
            {236, 238, 236}, {76, 154, 236},  {120, 124, 236}, {176, 98, 236},
            {228, 84, 236},  {236, 88, 180},  {236, 106, 100}, {212, 136, 32},
            {160, 170, 0},   {116, 196, 0},   {76, 208, 32},   {56, 204, 108},
            {56, 180, 204},  {60, 60, 60},    {0, 0, 0},       {0, 0, 0},
            {236, 238, 236}, {168, 204, 236}, {188, 188, 236}, {212, 178, 236},
            {236, 174, 236}, {236, 174, 212}, {236, 180, 176}, {228, 196, 144},
            {204, 210, 120}, {180, 222, 120}, {168, 226, 144}, {152, 226, 180},
            {160, 214, 228}, {160, 162, 160}, {0, 0, 0},       {0, 0, 0}
        };
        int[] palette = new int[64];
        for (int i = 0; i < 64; i++) {
            palette[i] = 0xFF000000 | (rgb[i][0] << 16) | (rgb[i][1] << 8) | rgb[i][2];
        }
        return palette;
    }
}
