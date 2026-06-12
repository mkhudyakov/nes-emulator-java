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
 *   ./gradlew run                       # open the window, then File > Open ROM…
 *   ./gradlew run --args="smb.nes"      # boot straight into a ROM
 * </pre>
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

        SwingUtilities.invokeLater(() -> {
            EmulatorFrame frame = new EmulatorFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                frame.loadRom(Path.of(args[0]));
            }
        });
    }
}
