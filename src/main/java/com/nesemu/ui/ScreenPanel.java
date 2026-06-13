package com.nesemu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import javax.swing.JPanel;

/**
 * A Swing panel that draws the PPU's 256x240 framebuffer.
 *
 * <p>The emulation thread fills an {@code int[]} every frame; this panel copies
 * that into a small CPU-side {@link BufferedImage} ({@code source}) and then
 * uploads it into a GPU-resident {@link VolatileImage} ({@code accelerated}).
 * The final scale to fill the component is performed from that VolatileImage, so
 * the expensive upscale runs on the GPU rather than pixel-by-pixel on the CPU.
 *
 * <p><b>Why the VolatileImage:</b> on a large / Retina display the destination
 * surface is millions of pixels, so a software nearest-neighbour upscale of the
 * 256x240 picture every frame is far too slow. A {@code VolatileImage} created
 * from the {@link GraphicsConfiguration} is guaranteed to live in VRAM, so the
 * scaled blit from it to the (also accelerated) Swing back buffer is a true
 * hardware operation under the default macOS Metal pipeline. A plain managed
 * {@code BufferedImage} gives no such guarantee. Nearest-neighbour scaling keeps
 * the pixels crisp.
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

    // GPU-resident copy used as the scale source. Created lazily once a
    // GraphicsConfiguration is available, and re-created if the OS evicts it.
    private VolatileImage accelerated;

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

    // --- temporary perf instrumentation ---
    private long paintWinStart = System.nanoTime();
    private long paintAcc = 0;
    private int paintCount = 0;

    @Override
    protected void paintComponent(Graphics g) {
        long pt0 = System.nanoTime();
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED);

        int panelW = getWidth();
        int panelH = getHeight();

        // Fit while preserving the 256:240 aspect ratio; letterbox the remainder.
        double scale = Math.min(panelW / (double) NES_WIDTH, panelH / (double) NES_HEIGHT);
        int drawW = (int) (NES_WIDTH * scale);
        int drawH = (int) (NES_HEIGHT * scale);
        int x = (panelW - drawW) / 2;
        int y = (panelH - drawH) / 2;

        // Upload the framebuffer to the GPU-resident buffer and scale from it.
        // Falls back to the CPU-side source if no accelerated buffer is available.
        VolatileImage scaleSource = refreshAccelerated();
        if (scaleSource != null) {
            g2.drawImage(scaleSource, x, y, drawW, drawH, null);
        } else {
            g2.drawImage(source, x, y, drawW, drawH, null);
        }

        // --- temporary perf instrumentation ---
        long pt1 = System.nanoTime();
        paintAcc += pt1 - pt0;
        paintCount++;
        if (pt1 - paintWinStart >= 1_000_000_000L) {
            System.out.printf("[perf] paints/s=%d  paint=%.2fms  size=%dx%d%n",
                    paintCount, paintAcc / 1e6 / paintCount, panelW, panelH);
            paintWinStart = pt1;
            paintAcc = 0;
            paintCount = 0;
        }
    }

    /**
     * Upload {@code source} into the VRAM-resident {@code accelerated} buffer,
     * (re)creating or revalidating it as the OS requires, and return it. Returns
     * {@code null} if no GraphicsConfiguration is available yet.
     */
    private VolatileImage refreshAccelerated() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc == null) {
            return null;
        }
        // VolatileImage contents can be lost at any time (display mode change,
        // VRAM eviction); the validate/contentsLost loop is the required dance.
        do {
            if (accelerated == null
                    || accelerated.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
                if (accelerated != null) {
                    accelerated.flush();
                }
                accelerated = gc.createCompatibleVolatileImage(NES_WIDTH, NES_HEIGHT);
            }
            Graphics ag = accelerated.createGraphics();
            try {
                ag.drawImage(source, 0, 0, null);
            } finally {
                ag.dispose();
            }
        } while (accelerated.contentsLost());
        return accelerated;
    }
}
