package com.nesemu.ui;

import com.nesemu.NesSystem;
import com.nesemu.apu.AudioOutput;
import com.nesemu.cartridge.Cartridge;
import com.nesemu.cartridge.InvalidRomException;
import com.nesemu.controller.Controller;
import java.awt.BorderLayout;
import java.awt.GraphicsDevice;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
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
     * Switch between windowed and real fullscreen. Uses the platform's
     * full-screen exclusive mode so the NES picture (scaled and letterboxed by
     * {@link ScreenPanel}) fills the entire display. The window must be
     * undecorated to enter exclusive mode, so we dispose and recreate the native
     * peer around the switch; the menu bar is hidden while fullscreen.
     */
    /** Enter fullscreen if not already in it (e.g. from a startup flag). */
    public void enterFullscreen() {
        if (!fullscreen) {
            toggleFullscreen();
        }
    }

    private void toggleFullscreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        if (!fullscreen) {
            if (!device.isFullScreenSupported()) {
                return;
            }
            dispose();
            setUndecorated(true);
            getJMenuBar().setVisible(false);
            device.setFullScreenWindow(this);
            fullscreen = true;
        } else {
            device.setFullScreenWindow(null);
            dispose();
            setUndecorated(false);
            getJMenuBar().setVisible(true);
            setVisible(true);
            fullscreen = false;
        }
        screen.requestFocusInWindow();
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

            if (audio.isAvailable()) {
                // Audio provides the timing; just yield to stay responsive.
                nextFrame = System.nanoTime();
            } else {
                // No sound: fall back to a sleep-based 60 fps clock.
                nextFrame += FRAME_NANOS;
                long sleep = nextFrame - System.nanoTime();
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    nextFrame = System.nanoTime();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Keyboard input
    // ------------------------------------------------------------------

    private void installKeyHandling() {
        KeyAdapter adapter = new KeyAdapter() {
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
        addKeyListener(adapter);
        screen.addKeyListener(adapter);
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
