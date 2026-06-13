package com.nesemu;

import com.nesemu.ui.EmulatorFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Application entry point. Creates the Swing window on the event dispatch thread
 * and, if a path is supplied on the command line, acts on it immediately.
 *
 * <pre>
 *   ./gradlew run                                   # open the window, then File > Open ROM…
 *   ./gradlew run --args="smb.nes"                  # boot straight into a ROM
 *   ./gradlew run --args="--fullscreen smb.nes"     # boot into a ROM, fullscreen
 *   ./gradlew run --args="--fullscreen /roms/"      # show a picker of *.nes in /roms/
 * </pre>
 *
 * Accepts an optional {@code --fullscreen} (or {@code -f}) flag to start in
 * fullscreen; the first non-flag argument is the path. If it points at a
 * directory, the emulator scans it for {@code *.nes} files and shows an
 * on-screen list to pick from.
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
        String pathArg = romPath;
        SwingUtilities.invokeLater(() -> {
            EmulatorFrame frame = new EmulatorFrame();
            frame.setVisible(true);
            if (startFullscreen) {
                frame.enterFullscreen();
            }
            if (pathArg != null) {
                Path path = Path.of(pathArg);
                if (Files.isDirectory(path)) {
                    List<Path> roms = listRoms(path);
                    if (roms.isEmpty()) {
                        JOptionPane.showMessageDialog(frame,
                                "No .nes files found in:\n" + path,
                                "No ROMs", JOptionPane.WARNING_MESSAGE);
                    } else {
                        frame.showRomChooser(roms);
                    }
                } else {
                    frame.loadRom(path);
                }
            }
        });
    }

    /** Top-level {@code *.nes} files in a directory, sorted by name. */
    private static List<Path> listRoms(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nes"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }
}
