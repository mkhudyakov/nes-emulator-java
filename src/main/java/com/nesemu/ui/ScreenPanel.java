package com.nesemu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * A Swing panel that draws the PPU's 256x240 ARGB framebuffer.
 *
 * <p>The emulation thread fills an {@code int[]} every frame; this panel copies
 * that into a small CPU-side {@link BufferedImage} ({@code source}) and then
 * blits it once into a hardware-accelerated, screen-compatible buffer
 * ({@code accelerated}). The final scale to fill the component is performed from
 * that accelerated buffer, so on fullscreen / large displays the expensive
 * upscale runs on the GPU rather than pixel-by-pixel on the CPU.
 *
 * <p><b>Why the two buffers:</b> grabbing the backing array of {@code source}
 * (for a fast {@code arraycopy} of the framebuffer) permanently marks it as
 * "unmanaged", so Java2D can never cache it in VRAM and every scaled draw of it
 * would fall back to software. The {@code accelerated} buffer is never touched
 * through its raw array, so Java2D keeps a VRAM copy and the scaled blit is
 * hardware-accelerated. Nearest-neighbour scaling keeps the pixels crisp.
 */
public final class ScreenPanel extends JPanel {

    public static final int NES_WIDTH = 256;
    public static final int NES_HEIGHT = 240;

    // TYPE_INT_RGB (not ARGB): the NES output is fully opaque, so dropping the
    // alpha channel lets Java2D blit/scale without per-pixel alpha compositing.
    private final BufferedImage source =
            new BufferedImage(NES_WIDTH, NES_HEIGHT, BufferedImage.TYPE_INT_RGB);
    private final int[] sourceData =
            ((java.awt.image.DataBufferInt) source.getRaster().getDataBuffer()).getData();

    // Hardware-accelerated, screen-compatible copy used as the scale source.
    // Created lazily once a GraphicsConfiguration is available.
    private BufferedImage accelerated;

    public ScreenPanel() {
        setPreferredSize(new Dimension(NES_WIDTH * 3, NES_HEIGHT * 3));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
    }

    /** Copy a fresh framebuffer in and request a repaint (call from any thread). */
    public void updateFrame(int[] framebuffer) {
        System.arraycopy(framebuffer, 0, sourceData, 0, sourceData.length);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);

        int panelW = getWidth();
        int panelH = getHeight();

        // Refresh the accelerated buffer from the CPU-side source. This 256x240
        // copy is cheap; Java2D re-caches it to VRAM for the scaled blit below.
        BufferedImage scaleSource = refreshAccelerated();

        // Fit while preserving the 256:240 aspect ratio; letterbox the remainder.
        double scale = Math.min(panelW / (double) NES_WIDTH, panelH / (double) NES_HEIGHT);
        int drawW = (int) (NES_WIDTH * scale);
        int drawH = (int) (NES_HEIGHT * scale);
        int x = (panelW - drawW) / 2;
        int y = (panelH - drawH) / 2;

        g2.drawImage(scaleSource, x, y, drawW, drawH, null);
    }

    /**
     * Copy {@code source} into the screen-compatible {@code accelerated} buffer
     * and return whichever is suitable to scale from. Falls back to {@code source}
     * if no GraphicsConfiguration is available yet.
     */
    private BufferedImage refreshAccelerated() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            return source;
        }
        if (accelerated == null) {
            accelerated = gc.createCompatibleImage(NES_WIDTH, NES_HEIGHT);
        }
        Graphics ag = accelerated.getGraphics();
        try {
            ag.drawImage(source, 0, 0, null);
        } finally {
            ag.dispose();
        }
        return accelerated;
    }
}
