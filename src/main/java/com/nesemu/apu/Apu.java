package com.nesemu.apu;

/**
 * The NES audio processing unit (the APU, part of the 2A03).
 *
 * <p>It has five sound channels — two pulse-wave channels, a triangle channel, a
 * noise channel, and a delta-modulation (DMC) sample channel — driven by a frame
 * sequencer that periodically clocks their envelopes, sweeps, and length
 * counters. The channels are mixed with the NES's characteristic non-linear
 * formula and resampled down to the host audio rate.
 *
 * <p>The CPU writes to registers {@code $4000-$4013}, {@code $4015}, and
 * {@code $4017}; {@link #clock()} is called once per CPU cycle by the system.
 * Generated samples are buffered and drained once per frame by the UI thread.
 */
public final class Apu {

    // NTSC timing.
    private static final double CPU_HZ = 1_789_773.0;
    public static final int SAMPLE_RATE = 44_100;
    private static final double CYCLES_PER_SAMPLE = CPU_HZ / SAMPLE_RATE;

    /** Length-counter load values, indexed by the 5-bit field in $4003 etc. */
    private static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    };

    /** Four duty cycles, 8 steps each (12.5%, 25%, 50%, 25% negated). */
    private static final int[][] DUTY_TABLE = {
            {0, 1, 0, 0, 0, 0, 0, 0},
            {0, 1, 1, 0, 0, 0, 0, 0},
            {0, 1, 1, 1, 1, 0, 0, 0},
            {1, 0, 0, 1, 1, 1, 1, 1}
    };

    /** 32-step triangle waveform (15→0→15). */
    private static final int[] TRIANGLE_SEQ = {
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    /** Noise timer periods (NTSC), indexed by the 4-bit field in $400E. */
    private static final int[] NOISE_PERIOD = {
            4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    };

    /** DMC rate table in CPU cycles (NTSC), indexed by the 4-bit field in $4010. */
    private static final int[] DMC_RATE = {
            428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54
    };

    /** Supplies a byte from CPU memory for DMC sample fetches. */
    public interface DmcReader {
        int read(int address);
    }

    private final Pulse pulse1 = new Pulse(true);
    private final Pulse pulse2 = new Pulse(false);
    private final Triangle triangle = new Triangle();
    private final Noise noise = new Noise();
    private final Dmc dmc = new Dmc();

    // Frame sequencer.
    private int frameCounter = 0;
    private boolean fiveStepMode = false;
    private boolean irqInhibit = false;
    private boolean frameIrq = false;

    private long cpuCycle = 0;
    private double sampleAccumulator = 0.0;

    // Output sample buffer (drained per frame), plus a DC-blocking filter.
    private final float[] sampleBuffer = new float[8192];
    private int sampleWriteIndex = 0;
    private float dcPrevIn = 0f;
    private float dcPrevOut = 0f;

    public void setDmcReader(DmcReader reader) {
        dmc.reader = reader;
    }

    // ------------------------------------------------------------------
    // Register interface
    // ------------------------------------------------------------------

    public void writeRegister(int addr, int value) {
        addr &= 0xFFFF;
        value &= 0xFF;
        switch (addr) {
            case 0x4000 -> pulse1.writeControl(value);
            case 0x4001 -> pulse1.writeSweep(value);
            case 0x4002 -> pulse1.writeTimerLow(value);
            case 0x4003 -> pulse1.writeTimerHigh(value);

            case 0x4004 -> pulse2.writeControl(value);
            case 0x4005 -> pulse2.writeSweep(value);
            case 0x4006 -> pulse2.writeTimerLow(value);
            case 0x4007 -> pulse2.writeTimerHigh(value);

            case 0x4008 -> triangle.writeLinear(value);
            case 0x400A -> triangle.writeTimerLow(value);
            case 0x400B -> triangle.writeTimerHigh(value);

            case 0x400C -> noise.writeControl(value);
            case 0x400E -> noise.writePeriod(value);
            case 0x400F -> noise.writeLength(value);

            case 0x4010 -> dmc.writeControl(value);
            case 0x4011 -> dmc.writeOutput(value);
            case 0x4012 -> dmc.writeAddress(value);
            case 0x4013 -> dmc.writeLength(value);

            case 0x4015 -> writeStatus(value);
            case 0x4017 -> writeFrameCounter(value);
            default -> { /* $4009, $400D are unused */ }
        }
    }

    /** $4015 write: enable/disable channels. */
    private void writeStatus(int value) {
        pulse1.setEnabled((value & 0x01) != 0);
        pulse2.setEnabled((value & 0x02) != 0);
        triangle.setEnabled((value & 0x04) != 0);
        noise.setEnabled((value & 0x08) != 0);
        dmc.setEnabled((value & 0x10) != 0);
    }

    /** $4015 read: channel status and IRQ flags. Reading clears the frame IRQ. */
    public int readStatus() {
        int status = 0;
        if (pulse1.lengthCounter > 0)   status |= 0x01;
        if (pulse2.lengthCounter > 0)   status |= 0x02;
        if (triangle.lengthCounter > 0) status |= 0x04;
        if (noise.lengthCounter > 0)    status |= 0x08;
        if (dmc.bytesRemaining > 0)     status |= 0x10;
        if (frameIrq)                   status |= 0x40;
        if (dmc.irqFlag)                status |= 0x80;
        frameIrq = false;
        return status;
    }

    /** $4017 write: frame-counter mode and IRQ inhibit. */
    private void writeFrameCounter(int value) {
        fiveStepMode = (value & 0x80) != 0;
        irqInhibit = (value & 0x40) != 0;
        if (irqInhibit) {
            frameIrq = false;
        }
        frameCounter = 0;
        // In 5-step mode, a quarter+half clock happens immediately.
        if (fiveStepMode) {
            clockQuarterFrame();
            clockHalfFrame();
        }
    }

    public boolean isIrqAsserted() {
        return frameIrq || dmc.irqFlag;
    }

    // ------------------------------------------------------------------
    // Clocking
    // ------------------------------------------------------------------

    /** Advance the APU by one CPU cycle. */
    public void clock() {
        // The triangle timer is clocked every CPU cycle; the pulse and noise
        // timers, and the frame sequencer, run at half that (one "APU cycle").
        triangle.clockTimer();
        dmc.clockTimer();
        if ((cpuCycle & 1L) == 0L) {
            pulse1.clockTimer();
            pulse2.clockTimer();
            noise.clockTimer();
        }

        clockFrameSequencer();
        cpuCycle++;

        // Resample down to the host audio rate.
        sampleAccumulator += 1.0;
        if (sampleAccumulator >= CYCLES_PER_SAMPLE) {
            sampleAccumulator -= CYCLES_PER_SAMPLE;
            emitSample();
        }
    }

    /**
     * The frame sequencer fires quarter-frame (envelope/linear) and half-frame
     * (length/sweep) clocks at fixed CPU-cycle offsets within each frame.
     */
    private void clockFrameSequencer() {
        if (!fiveStepMode) {
            switch (frameCounter) {
                case 7457  -> clockQuarterFrame();
                case 14913 -> { clockQuarterFrame(); clockHalfFrame(); }
                case 22371 -> clockQuarterFrame();
                case 29829 -> {
                    clockQuarterFrame();
                    clockHalfFrame();
                    if (!irqInhibit) {
                        frameIrq = true;
                    }
                }
                default -> { }
            }
            frameCounter++;
            if (frameCounter >= 29830) {
                frameCounter = 0;
            }
        } else {
            switch (frameCounter) {
                case 7457  -> clockQuarterFrame();
                case 14913 -> { clockQuarterFrame(); clockHalfFrame(); }
                case 22371 -> clockQuarterFrame();
                case 37281 -> { clockQuarterFrame(); clockHalfFrame(); }
                default -> { }
            }
            frameCounter++;
            if (frameCounter >= 37282) {
                frameCounter = 0;
            }
        }
    }

    private void clockQuarterFrame() {
        pulse1.envelope.clock();
        pulse2.envelope.clock();
        noise.envelope.clock();
        triangle.clockLinear();
    }

    private void clockHalfFrame() {
        pulse1.clockLengthAndSweep();
        pulse2.clockLengthAndSweep();
        triangle.clockLength();
        noise.clockLength();
    }

    // ------------------------------------------------------------------
    // Mixing and sample output
    // ------------------------------------------------------------------

    private void emitSample() {
        int p = pulse1.output() + pulse2.output();
        double pulseOut = (p == 0) ? 0.0 : 95.88 / (8128.0 / p + 100.0);

        double tnd = triangle.output() / 8227.0
                + noise.output() / 12241.0
                + dmc.output() / 22638.0;
        double tndOut = (tnd == 0.0) ? 0.0 : 159.79 / (1.0 / tnd + 100.0);

        float raw = (float) (pulseOut + tndOut); // roughly 0.0 .. 1.0

        // First-order DC blocker centres the signal around zero (removes the big
        // DC offset and the start-up pop), mimicking the console's coupling.
        float filtered = raw - dcPrevIn + 0.9995f * dcPrevOut;
        dcPrevIn = raw;
        dcPrevOut = filtered;

        if (sampleWriteIndex < sampleBuffer.length) {
            sampleBuffer[sampleWriteIndex++] = filtered;
        }
    }

    /**
     * Copy and clear the samples produced since the last drain.
     *
     * @param out destination array (should be large enough for a frame's worth)
     * @return the number of samples written into {@code out}
     */
    public int drainSamples(float[] out) {
        int n = Math.min(sampleWriteIndex, out.length);
        System.arraycopy(sampleBuffer, 0, out, 0, n);
        sampleWriteIndex = 0;
        return n;
    }

    public void reset() {
        pulse1.setEnabled(false);
        pulse2.setEnabled(false);
        triangle.setEnabled(false);
        noise.setEnabled(false);
        dmc.setEnabled(false);
        frameCounter = 0;
        fiveStepMode = false;
        irqInhibit = false;
        frameIrq = false;
        cpuCycle = 0;
        sampleAccumulator = 0.0;
        sampleWriteIndex = 0;
        dcPrevIn = 0f;
        dcPrevOut = 0f;
    }

    // ==================================================================
    // Shared sub-units
    // ==================================================================

    /** Volume envelope generator used by the pulse and noise channels. */
    private static final class Envelope {
        boolean start = false;
        boolean constant = false;
        boolean loop = false;
        int volumeOrPeriod = 0; // 4-bit
        int divider = 0;
        int decay = 0;

        void clock() {
            if (start) {
                start = false;
                decay = 15;
                divider = volumeOrPeriod;
            } else if (divider == 0) {
                divider = volumeOrPeriod;
                if (decay > 0) {
                    decay--;
                } else if (loop) {
                    decay = 15;
                }
            } else {
                divider--;
            }
        }

        int volume() {
            return constant ? volumeOrPeriod : decay;
        }
    }

    // ==================================================================
    // Pulse channel
    // ==================================================================

    private static final class Pulse {
        private final boolean isPulse1; // ones-complement difference in sweep
        final Envelope envelope = new Envelope();

        boolean enabled = false;
        int duty = 0;
        int sequenceStep = 0;
        int timerPeriod = 0;
        int timerValue = 0;
        int lengthCounter = 0;
        boolean lengthHalt = false;

        // Sweep unit.
        boolean sweepEnabled = false;
        boolean sweepNegate = false;
        boolean sweepReload = false;
        int sweepPeriod = 0;
        int sweepShift = 0;
        int sweepDivider = 0;

        Pulse(boolean isPulse1) {
            this.isPulse1 = isPulse1;
        }

        void writeControl(int value) {
            duty = (value >> 6) & 0x03;
            lengthHalt = (value & 0x20) != 0;
            envelope.loop = lengthHalt;
            envelope.constant = (value & 0x10) != 0;
            envelope.volumeOrPeriod = value & 0x0F;
        }

        void writeSweep(int value) {
            sweepEnabled = (value & 0x80) != 0;
            sweepPeriod = (value >> 4) & 0x07;
            sweepNegate = (value & 0x08) != 0;
            sweepShift = value & 0x07;
            sweepReload = true;
        }

        void writeTimerLow(int value) {
            timerPeriod = (timerPeriod & 0x700) | value;
        }

        void writeTimerHigh(int value) {
            timerPeriod = (timerPeriod & 0x0FF) | ((value & 0x07) << 8);
            if (enabled) {
                lengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F];
            }
            sequenceStep = 0;
            envelope.start = true;
        }

        void setEnabled(boolean on) {
            enabled = on;
            if (!on) {
                lengthCounter = 0;
            }
        }

        void clockTimer() {
            if (timerValue == 0) {
                timerValue = timerPeriod;
                sequenceStep = (sequenceStep + 1) & 0x07;
            } else {
                timerValue--;
            }
        }

        void clockLengthAndSweep() {
            if (!lengthHalt && lengthCounter > 0) {
                lengthCounter--;
            }
            // Sweep.
            int target = targetPeriod();
            if (sweepDivider == 0 && sweepEnabled && sweepShift > 0 && !isMuted(target)) {
                timerPeriod = target;
            }
            if (sweepDivider == 0 || sweepReload) {
                sweepDivider = sweepPeriod;
                sweepReload = false;
            } else {
                sweepDivider--;
            }
        }

        private int targetPeriod() {
            int change = timerPeriod >> sweepShift;
            if (sweepNegate) {
                int t = timerPeriod - change - (isPulse1 ? 1 : 0);
                return Math.max(t, 0);
            }
            return timerPeriod + change;
        }

        private boolean isMuted(int target) {
            return timerPeriod < 8 || target > 0x7FF;
        }

        int output() {
            if (!enabled || lengthCounter == 0) {
                return 0;
            }
            if (DUTY_TABLE[duty][sequenceStep] == 0) {
                return 0;
            }
            if (isMuted(targetPeriod())) {
                return 0;
            }
            return envelope.volume();
        }
    }

    // ==================================================================
    // Triangle channel
    // ==================================================================

    private static final class Triangle {
        boolean enabled = false;
        int timerPeriod = 0;
        int timerValue = 0;
        int sequenceStep = 0;
        int lengthCounter = 0;
        boolean lengthHalt = false; // also the linear-counter control flag

        int linearCounter = 0;
        int linearReloadValue = 0;
        boolean linearReload = false;

        void writeLinear(int value) {
            lengthHalt = (value & 0x80) != 0;
            linearReloadValue = value & 0x7F;
        }

        void writeTimerLow(int value) {
            timerPeriod = (timerPeriod & 0x700) | value;
        }

        void writeTimerHigh(int value) {
            timerPeriod = (timerPeriod & 0x0FF) | ((value & 0x07) << 8);
            if (enabled) {
                lengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F];
            }
            linearReload = true;
        }

        void setEnabled(boolean on) {
            enabled = on;
            if (!on) {
                lengthCounter = 0;
            }
        }

        void clockTimer() {
            if (timerValue == 0) {
                timerValue = timerPeriod;
                // Only advance the waveform while both gates are open.
                if (lengthCounter > 0 && linearCounter > 0) {
                    sequenceStep = (sequenceStep + 1) & 0x1F;
                }
            } else {
                timerValue--;
            }
        }

        void clockLinear() {
            if (linearReload) {
                linearCounter = linearReloadValue;
            } else if (linearCounter > 0) {
                linearCounter--;
            }
            if (!lengthHalt) {
                linearReload = false;
            }
        }

        void clockLength() {
            if (!lengthHalt && lengthCounter > 0) {
                lengthCounter--;
            }
        }

        int output() {
            // Silencing at very high frequencies avoids a click-prone tone.
            if (timerPeriod < 2) {
                return 0;
            }
            return TRIANGLE_SEQ[sequenceStep];
        }
    }

    // ==================================================================
    // Noise channel
    // ==================================================================

    private static final class Noise {
        final Envelope envelope = new Envelope();

        boolean enabled = false;
        boolean lengthHalt = false;
        int lengthCounter = 0;
        int timerPeriod = 0;
        int timerValue = 0;
        boolean mode = false;          // short-period (tonal) mode
        int shiftRegister = 1;

        void writeControl(int value) {
            lengthHalt = (value & 0x20) != 0;
            envelope.loop = lengthHalt;
            envelope.constant = (value & 0x10) != 0;
            envelope.volumeOrPeriod = value & 0x0F;
        }

        void writePeriod(int value) {
            mode = (value & 0x80) != 0;
            timerPeriod = NOISE_PERIOD[value & 0x0F];
        }

        void writeLength(int value) {
            if (enabled) {
                lengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F];
            }
            envelope.start = true;
        }

        void setEnabled(boolean on) {
            enabled = on;
            if (!on) {
                lengthCounter = 0;
            }
        }

        void clockTimer() {
            if (timerValue == 0) {
                timerValue = timerPeriod;
                int feedbackBit = mode ? (shiftRegister >> 6) : (shiftRegister >> 1);
                int feedback = (shiftRegister ^ feedbackBit) & 0x01;
                shiftRegister = (shiftRegister >> 1) | (feedback << 14);
            } else {
                timerValue--;
            }
        }

        void clockLength() {
            if (!lengthHalt && lengthCounter > 0) {
                lengthCounter--;
            }
        }

        int output() {
            if (!enabled || lengthCounter == 0 || (shiftRegister & 0x01) != 0) {
                return 0;
            }
            return envelope.volume();
        }
    }

    // ==================================================================
    // DMC (delta modulation) channel
    // ==================================================================

    private static final class Dmc {
        DmcReader reader;

        boolean enabled = false;
        boolean irqEnabled = false;
        boolean loop = false;
        boolean irqFlag = false;

        int ratePeriod = DMC_RATE[0];
        int rateCounter = DMC_RATE[0];
        int outputLevel = 0;           // 7-bit DAC

        int sampleAddress = 0xC000;
        int sampleLength = 1;
        int currentAddress = 0xC000;
        int bytesRemaining = 0;

        int shiftRegister = 0;
        int bitsRemaining = 8;
        int sampleByte = 0;
        boolean sampleBufferEmpty = true;
        boolean silence = true;

        void writeControl(int value) {
            irqEnabled = (value & 0x80) != 0;
            loop = (value & 0x40) != 0;
            ratePeriod = DMC_RATE[value & 0x0F];
            if (!irqEnabled) {
                irqFlag = false;
            }
        }

        void writeOutput(int value) {
            outputLevel = value & 0x7F;
        }

        void writeAddress(int value) {
            sampleAddress = 0xC000 | (value << 6);
        }

        void writeLength(int value) {
            sampleLength = (value << 4) + 1;
        }

        void setEnabled(boolean on) {
            enabled = on;
            irqFlag = false;
            if (!on) {
                bytesRemaining = 0;
            } else if (bytesRemaining == 0) {
                currentAddress = sampleAddress;
                bytesRemaining = sampleLength;
            }
        }

        void clockTimer() {
            fillBuffer();
            if (rateCounter == 0) {
                rateCounter = ratePeriod;
                clockOutput();
            } else {
                rateCounter--;
            }
        }

        private void fillBuffer() {
            if (sampleBufferEmpty && bytesRemaining > 0 && reader != null) {
                sampleByte = reader.read(currentAddress) & 0xFF;
                sampleBufferEmpty = false;
                currentAddress = (currentAddress + 1) & 0xFFFF;
                if (currentAddress == 0x0000) {
                    currentAddress = 0x8000;
                }
                bytesRemaining--;
                if (bytesRemaining == 0) {
                    if (loop) {
                        currentAddress = sampleAddress;
                        bytesRemaining = sampleLength;
                    } else if (irqEnabled) {
                        irqFlag = true;
                    }
                }
            }
        }

        private void clockOutput() {
            if (!silence) {
                if ((shiftRegister & 0x01) != 0) {
                    if (outputLevel <= 125) {
                        outputLevel += 2;
                    }
                } else if (outputLevel >= 2) {
                    outputLevel -= 2;
                }
            }
            shiftRegister >>= 1;
            bitsRemaining--;
            if (bitsRemaining == 0) {
                bitsRemaining = 8;
                if (sampleBufferEmpty) {
                    silence = true;
                } else {
                    silence = false;
                    shiftRegister = sampleByte;
                    sampleBufferEmpty = true;
                }
            }
        }

        int output() {
            return outputLevel;
        }
    }
}
