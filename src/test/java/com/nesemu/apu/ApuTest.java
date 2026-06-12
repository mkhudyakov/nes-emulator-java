package com.nesemu.apu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApuTest {

    private Apu apu;

    @BeforeEach
    void setUp() {
        apu = new Apu();
        // 4-step mode with IRQ inhibited, so the frame counter never asserts IRQ.
        apu.writeRegister(0x4017, 0x40);
    }

    /** Clock the APU for the given number of CPU cycles, collecting samples. */
    private float[] run(int cycles) {
        float[] all = new float[cycles]; // generous upper bound
        float[] chunk = new float[4096];
        int total = 0;
        for (int i = 0; i < cycles; i++) {
            apu.clock();
            if ((i & 0x0FFF) == 0x0FFF) {
                int n = apu.drainSamples(chunk);
                System.arraycopy(chunk, 0, all, total, n);
                total += n;
            }
        }
        int n = apu.drainSamples(chunk);
        System.arraycopy(chunk, 0, all, total, n);
        total += n;
        float[] out = new float[total];
        System.arraycopy(all, 0, out, 0, total);
        return out;
    }

    private static float maxAbs(float[] xs) {
        float m = 0f;
        for (float x : xs) {
            m = Math.max(m, Math.abs(x));
        }
        return m;
    }

    @Test
    void resamplesToApproximatelyFortyFourKhz() {
        // One second of CPU cycles should yield about 44,100 samples.
        float[] samples = run(1_789_773);
        assertTrue(Math.abs(samples.length - 44_100) < 200,
                "expected ~44100 samples, got " + samples.length);
    }

    @Test
    void pulseChannelProducesAudibleWaveform() {
        apu.writeRegister(0x4015, 0x01); // enable pulse 1
        apu.writeRegister(0x4000, 0x9F); // duty 2, constant volume 15
        apu.writeRegister(0x4002, 0x40); // timer low
        apu.writeRegister(0x4003, 0x08); // timer high + length load

        float[] samples = run(40_000);
        assertTrue(samples.length > 800, "too few samples: " + samples.length);
        assertTrue(maxAbs(samples) > 0.001f, "pulse output was silent");
    }

    @Test
    void triangleChannelProducesAudibleWaveform() {
        apu.writeRegister(0x4015, 0x04); // enable triangle
        apu.writeRegister(0x4008, 0x7F); // linear counter load, control set
        apu.writeRegister(0x400A, 0x80); // timer low
        apu.writeRegister(0x400B, 0x08); // timer high + length load

        float[] samples = run(40_000);
        assertTrue(maxAbs(samples) > 0.001f, "triangle output was silent");
    }

    @Test
    void disabledChannelIsSilent() {
        // Configure pulse 1 but never enable it via $4015.
        apu.writeRegister(0x4000, 0x9F);
        apu.writeRegister(0x4002, 0x40);
        apu.writeRegister(0x4003, 0x08);

        float[] samples = run(20_000);
        assertEquals(0f, maxAbs(samples), 1e-6f);
    }

    @Test
    void statusReportsEnabledChannelLength() {
        apu.writeRegister(0x4015, 0x01); // enable pulse 1
        apu.writeRegister(0x4003, 0x08); // load a non-zero length counter
        assertTrue((apu.readStatus() & 0x01) != 0, "length counter should be active");

        apu.writeRegister(0x4015, 0x00); // disable -> length cleared
        assertEquals(0, apu.readStatus() & 0x01);
    }
}
