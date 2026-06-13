package com.nesemu.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/**
 * A Swing panel that draws the PPU's 256x240 ARGB framebuffer.
 *
 * <p>The emulation thread fills an {@code int[]} every frame; this panel copies
 * that into a {@link BufferedImage} and scales it to fill the component while
 * preserving the NES's 256:240 aspect ratio. Nearest-neighbour scaling keeps the
 * pixels crisp.
 */
public final class ScreenPanel extends JPanel {

    public static final int NES_WIDTH = 256;
    public static final int NES_HEIGHT = 240;

    // TYPE_INT_RGB (not ARGB): the NES output is fully opaque, so dropping the
    // alpha channel lets Java2D blit/scale without per-pixel alpha compositing —
    // a meaningful saving on weak GPUs (e.g. Raspberry Pi) when scaled up.
    private final BufferedImage image =
            new BufferedImage(NES_WIDTH, NES_HEIGHT, BufferedImage.TYPE_INT_RGB);
    private final int[] imageData =
            ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();

    public ScreenPanel() {
        setPreferredSize(new Dimension(NES_WIDTH * 3, NES_HEIGHT * 3));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
    }

    /** Copy a fresh framebuffer in and request a repaint (call from any thread). */
    public void updateFrame(int[] framebuffer) {
        System.arraycopy(framebuffer, 0, imageData, 0, imageData.length);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int panelW = getWidth();
        int panelH = getHeight();

        // Fit while preserving the 256:240 aspect ratio; letterbox the remainder.
        double scale = Math.min(panelW / (double) NES_WIDTH, panelH / (double) NES_HEIGHT);
        int drawW = (int) (NES_WIDTH * scale);
        int drawH = (int) (NES_HEIGHT * scale);
        int x = (panelW - drawW) / 2;
        int y = (panelH - drawH) / 2;

        g2.drawImage(image, x, y, drawW, drawH, null);
    }
}
