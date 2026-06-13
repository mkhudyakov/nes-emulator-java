package com.nesemu.ui;

import com.nesemu.NesSystem;
import com.nesemu.apu.AudioOutput;
import com.nesemu.cartridge.Cartridge;
import com.nesemu.cartridge.InvalidRomException;
import com.nesemu.controller.Controller;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The main application window: a menu bar for loading ROMs and controlling
 * emulation, the {@link ScreenPanel} display, keyboard input handling, and the
 * background thread that drives the {@link NesSystem} at roughly 60 frames per
 * second.
 *
 * <h2>Controls</h2>
 * <pre>
 *   Arrow keys  D-pad         Z  A button     Enter        Start
 *                             X  B button     Right Shift  Select
 * </pre>
 */
public final class EmulatorFrame extends JFrame {

    private static final double TARGET_FPS = 60.0988;          // NTSC frame rate
    private static final long FRAME_NANOS = (long) (1_000_000_000L / TARGET_FPS);

    private final NesSystem nes = new NesSystem();
    private final ScreenPanel screen = new ScreenPanel();
    private final AudioOutput audio = new AudioOutput();
    private final float[] sampleBuffer = new float[4096];

    private volatile boolean running = false;
    private volatile boolean romLoaded = false;
    private boolean fullscreen = false;
    private Thread emulationThread;

    /** The on-screen ROM picker, when shown; null once a ROM is chosen. */
    private Component romChooser;

    /**
     * The focusable {@link JList} inside {@link #romChooser} (the panel itself is
     * not focusable, and only the list carries the arrow-key listener); null when
     * no chooser is shown. Tracked so focus can be restored to it after the
     * fullscreen toggle disposes and recreates the window.
     */
    private JList<Path> romList;

    public EmulatorFrame() {
        super("NES Emulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(screen, BorderLayout.CENTER);
        setJMenuBar(buildMenuBar());
        installKeyHandling();
        pack();
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem open = new JMenuItem("Open ROM…");
        open.addActionListener(e -> openRom());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        fileMenu.add(open);
        fileMenu.addSeparator();
        fileMenu.add(exit);

        JMenu emuMenu = new JMenu("Emulation");
        JMenuItem reset = new JMenuItem("Reset");
        reset.addActionListener(e -> resetSystem());
        JMenuItem pause = new JMenuItem("Pause / Resume");
        pause.addActionListener(e -> togglePause());
        emuMenu.add(reset);
        emuMenu.add(pause);

        JMenu viewMenu = new JMenu("View");
        JMenuItem fullscreen = new JMenuItem("Toggle Fullscreen");
        fullscreen.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0));
        fullscreen.addActionListener(e -> toggleFullscreen());
        viewMenu.add(fullscreen);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem controls = new JMenuItem("Controls");
        controls.addActionListener(e -> showControls());
        helpMenu.add(controls);

        bar.add(fileMenu);
        bar.add(emuMenu);
        bar.add(viewMenu);
        bar.add(helpMenu);
        return bar;
    }

    private void openRom() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open NES ROM");
        chooser.setFileFilter(new FileNameExtensionFilter("iNES ROM (*.nes)", "nes"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        loadRom(file.toPath());
    }

    /** Load a ROM from disk, reporting any problem in a dialog. */
    public void loadRom(Path path) {
        stopEmulation();
        try {
            Cartridge cartridge = Cartridge.fromFile(path);
            nes.insertCartridge(cartridge);
            nes.reset();
            romLoaded = true;
            setTitle("NES Emulator — " + path.getFileName());
            startEmulation();
        } catch (InvalidRomException ex) {
            romLoaded = false;
            showError("Invalid ROM", ex.getMessage());
        } catch (IOException ex) {
            romLoaded = false;
            showError("Could not read file", ex.getMessage());
        } catch (RuntimeException ex) {
            romLoaded = false;
            showError("Failed to load ROM", String.valueOf(ex.getMessage()));
        }
    }

    private void resetSystem() {
        if (romLoaded) {
            nes.reset();
            audio.flush();
        }
    }

    private void togglePause() {
        if (!romLoaded) {
            return;
        }
        if (running) {
            stopEmulation();
        } else {
            startEmulation();
        }
    }

    /**
     * Switch between windowed and fullscreen. Uses a borderless ("fake")
     * fullscreen window — an undecorated frame sized to fill the display —
     * rather than the platform's full-screen <em>exclusive</em> mode.
     *
     * <p>Exclusive mode ({@code setFullScreenWindow}) disables Java2D's normal
     * hardware-accelerated blit for passive Swing repaints, so the per-frame
     * upscale done in {@link ScreenPanel#paintComponent} falls back to slow
     * software rendering (badly so on macOS), dragging the whole emulation down
     * via back-pressure. A borderless window keeps the accelerated pipeline that
     * already performs well when windowed; it just scales to a larger surface.
     * The menu bar is hidden while fullscreen.
     */
    /** Enter fullscreen if not already in it (e.g. from a startup flag). */
    public void enterFullscreen() {
        if (!fullscreen) {
            toggleFullscreen();
        }
    }

    private java.awt.Rectangle windowedBounds;

    private void toggleFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        if (!fullscreen) {
            windowedBounds = getBounds();
            dispose();
            setUndecorated(true);
            getJMenuBar().setVisible(false);
            setBounds(device.getDefaultConfiguration().getBounds());
            setVisible(true);
            fullscreen = true;
        } else {
            dispose();
            setUndecorated(false);
            getJMenuBar().setVisible(true);
            if (windowedBounds != null) {
                setBounds(windowedBounds);
            }
            setVisible(true);
            fullscreen = false;
        }
        // Restore keyboard focus to the active view. The dispose/recreate above
        // drops focus, and the chooser's key handling lives on the JList (not the
        // panel), so focus the list directly. Defer until the recreated window is
        // realized, otherwise requestFocusInWindow is a no-op.
        Component focusTarget = (romChooser != null) ? romList : screen;
        if (focusTarget != null) {
            SwingUtilities.invokeLater(focusTarget::requestFocusInWindow);
        }
    }

    // ------------------------------------------------------------------
    // ROM chooser
    // ------------------------------------------------------------------

    /**
     * Replace the display with an on-screen list of ROMs (black background,
     * white text). Navigate with the arrow keys and press Enter, or double-click,
     * to load one. Works in both windowed and fullscreen mode.
     */
    public void showRomChooser(List<Path> roms) {
        stopEmulation();

        DefaultListModel<Path> model = new DefaultListModel<>();
        roms.forEach(model::addElement);

        JList<Path> list = new JList<>(model);
        romList = list;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setBackground(Color.BLACK);
        list.setForeground(Color.WHITE);
        list.setSelectionBackground(Color.WHITE);
        list.setSelectionForeground(Color.BLACK);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 24));
        list.setFixedCellHeight(36);
        list.setBorder(BorderFactory.createEmptyBorder(8, 24, 8, 24));
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> jl, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(jl, value, index, selected, focused);
                setText(((Path) value).getFileName().toString());
                return this;
            }
        });

        // Enter / double-click loads the highlighted ROM.
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    chooseRom(list.getSelectedValue());
                } else if (e.getKeyCode() == KeyEvent.VK_F11) {
                    toggleFullscreen();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE && fullscreen) {
                    toggleFullscreen();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    chooseRom(list.getSelectedValue());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.BLACK);
        // Avoid JViewport's default BLIT_SCROLL_MODE: it scrolls via
        // Graphics.copyArea, which is effectively unaccelerated on macOS and
        // crawls on a full-screen Retina surface. SIMPLE_SCROLL_MODE never calls
        // copyArea — it just repaints the (few) visible cells.
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);

        JLabel title = new JLabel("Select a game  —  ↑/↓ and Enter");
        title.setForeground(Color.WHITE);
        title.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        title.setBorder(BorderFactory.createEmptyBorder(24, 24, 16, 24));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.BLACK);
        panel.add(title, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        remove(screen);
        if (romChooser != null) {
            remove(romChooser);
        }
        romChooser = panel;
        add(panel, BorderLayout.CENTER);
        revalidate();
        repaint();
        list.requestFocusInWindow();
    }

    private void chooseRom(Path rom) {
        if (rom == null) {
            return;
        }
        remove(romChooser);
        romChooser = null;
        romList = null;
        add(screen, BorderLayout.CENTER);
        revalidate();
        repaint();
        screen.requestFocusInWindow();
        loadRom(rom);
    }

    // ------------------------------------------------------------------
    // Emulation thread
    // ------------------------------------------------------------------

    private void startEmulation() {
        if (running || !romLoaded) {
            return;
        }
        running = true;
        emulationThread = new Thread(this::emulationLoop, "nes-emulation");
        emulationThread.setDaemon(true);
        emulationThread.start();
    }

    private void stopEmulation() {
        running = false;
        Thread t = emulationThread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            emulationThread = null;
        }
        audio.flush();
    }

    private void emulationLoop() {
        long nextFrame = System.nanoTime();
        while (running) {
            nes.stepFrame();
            screen.updateFrame(nes.getFramebuffer());

            // Push this frame's audio. When the sound line is active its buffer
            // blocks here as needed, pacing emulation to real time.
            int produced = nes.getApu().drainSamples(sampleBuffer);
            audio.write(sampleBuffer, produced);

//            if (audio.isAvailable()) {
//                // Audio provides the timing; just yield to stay responsive.
//                nextFrame = System.nanoTime();
//            } else {
//                // No sound: fall back to a sleep-based 60 fps clock.
//                nextFrame += FRAME_NANOS;
//                long sleep = nextFrame - System.nanoTime();
//                if (sleep > 0) {
//                    try {
//                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
//                    } catch (InterruptedException e) {
//                        Thread.currentThread().interrupt();
//                        return;
//                    }
//                } else {
//                    nextFrame = System.nanoTime();
//                }
//            }
        }
    }

    // ------------------------------------------------------------------
    // Keyboard input
    // ------------------------------------------------------------------

    private final KeyAdapter inputAdapter = new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_F11) {
                toggleFullscreen();
                return;
            }
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE && fullscreen) {
                toggleFullscreen();
                return;
            }
            setButton(e, true);
        }

        @Override
        public void keyReleased(KeyEvent e) {
            setButton(e, false);
        }
    };

    private void installKeyHandling() {
        addKeyListener(inputAdapter);
        screen.addKeyListener(inputAdapter);
        screen.requestFocusInWindow();
    }

    private void setButton(KeyEvent e, boolean pressed) {
        Controller pad = nes.getController1();
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP    -> pad.setButton(Controller.BUTTON_UP, pressed);
            case KeyEvent.VK_DOWN  -> pad.setButton(Controller.BUTTON_DOWN, pressed);
            case KeyEvent.VK_LEFT  -> pad.setButton(Controller.BUTTON_LEFT, pressed);
            case KeyEvent.VK_RIGHT -> pad.setButton(Controller.BUTTON_RIGHT, pressed);
            case KeyEvent.VK_Z     -> pad.setButton(Controller.BUTTON_A, pressed);
            case KeyEvent.VK_X     -> pad.setButton(Controller.BUTTON_B, pressed);
            case KeyEvent.VK_ENTER -> pad.setButton(Controller.BUTTON_START, pressed);
            case KeyEvent.VK_SHIFT -> {
                // Map only the right shift key to Select.
                if (e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT) {
                    pad.setButton(Controller.BUTTON_SELECT, pressed);
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showError(String title, String message) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE));
    }

    private void showControls() {
        String msg = """
                Movement      Arrow keys
                A button      Z
                B button      X
                Start         Enter
                Select        Right Shift

                Load a ROM with File > Open ROM…
                You must supply your own legally-obtained .nes file.""";
        JOptionPane.showMessageDialog(this, msg, "Controls", JOptionPane.INFORMATION_MESSAGE);
    }
}
