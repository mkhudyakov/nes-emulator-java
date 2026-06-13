package com.nesemu;

import com.nesemu.ui.EmulatorFrame;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Application entry point. Creates the Swing window on the event dispatch thread
 * and, if a ROM path is supplied on the command line, loads it immediately.
 *
 * <pre>
 *   ./gradlew run                                   # open the window, then File > Open ROM…
 *   ./gradlew run --args="smb.nes"                  # boot straight into a ROM
 *   ./gradlew run --args="--fullscreen smb.nes"     # boot into a ROM, fullscreen
 * </pre>
 *
 * Accepts an optional {@code --fullscreen} (or {@code -f}) flag to start in
 * fullscreen; the first non-flag argument is treated as the ROM path.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to the default look and feel.
        }

        boolean fullscreen = false;
        String romPath = null;
        for (String arg : args) {
            if (arg.equals("--fullscreen") || arg.equals("-f")) {
                fullscreen = true;
            } else if (romPath == null) {
                romPath = arg;
            }
        }

        boolean startFullscreen = fullscreen;
        String rom = romPath;
        SwingUtilities.invokeLater(() -> {
            EmulatorFrame frame = new EmulatorFrame();
            frame.setVisible(true);
            if (rom != null) {
                frame.loadRom(Path.of(rom));
            }
            if (startFullscreen) {
                frame.enterFullscreen();
            }
        });
    }
}
