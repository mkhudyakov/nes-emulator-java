package com.nesemu.apu;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays the APU's floating-point samples through the host sound system as
 * 16-bit signed mono PCM.
 *
 * <p>The {@link SourceDataLine}'s internal buffer provides the back-pressure
 * that keeps emulation roughly in step with real time: when the buffer is full,
 * {@link #write} blocks until the sound card has consumed enough samples. If no
 * audio line is available the emulator simply runs silently.
 */
public final class AudioOutput {

    private SourceDataLine line;
    private byte[] byteBuffer = new byte[0];
    private float gain = 0.9f;
    private boolean available = false;

    public AudioOutput() {
        try {
            AudioFormat format = new AudioFormat(
                    Apu.SAMPLE_RATE, 16, 1, true, false); // signed, little-endian
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            // ~4 frames of latency: enough to absorb scheduling jitter.
            int bufferBytes = (Apu.SAMPLE_RATE / 15) * 2;
            line.open(format, bufferBytes);
            line.start();
            available = true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Convert and play {@code count} samples from {@code samples}. */
    public void write(float[] samples, int count) {
        if (!available || count <= 0) {
            return;
        }
        int needed = count * 2;
        if (byteBuffer.length < needed) {
            byteBuffer = new byte[needed];
        }
        for (int i = 0; i < count; i++) {
            int s = Math.round(samples[i] * gain * 32767f);
            if (s > 32767) {
                s = 32767;
            } else if (s < -32768) {
                s = -32768;
            }
            byteBuffer[i * 2] = (byte) (s & 0xFF);
            byteBuffer[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        line.write(byteBuffer, 0, needed);
    }

    /** Discard any buffered audio (used on reset/pause to avoid stale sound). */
    public void flush() {
        if (available) {
            line.flush();
        }
    }

    public void close() {
        if (available) {
            line.drain();
            line.stop();
            line.close();
            available = false;
        }
    }
}
