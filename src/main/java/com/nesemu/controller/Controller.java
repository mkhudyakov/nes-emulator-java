package com.nesemu.controller;

/**
 * Emulates a standard NES controller and its shift-register protocol.
 *
 * <p>The real hardware works like this:</p>
 * <ol>
 *   <li>The CPU writes to $4016 with bit 0 set ("strobe high"). While strobe is
 *       high the controller continuously reloads the current button states into
 *       its internal shift register.</li>
 *   <li>The CPU writes bit 0 clear ("strobe low"), latching the snapshot.</li>
 *   <li>Each read of $4016 returns the next button in the fixed order
 *       A, B, Select, Start, Up, Down, Left, Right (one bit per read, LSB first)
 *       and shifts the register. After 8 reads the register is empty and reads
 *       return 1 on official hardware.</li>
 * </ol>
 *
 * <p>This class is thread-safe for the button-setting side (the Swing event
 * thread sets buttons; the emulation thread reads them).</p>
 */
public final class Controller {

    // Bit positions in the latched state, matching NES read order.
    public static final int BUTTON_A      = 0;
    public static final int BUTTON_B      = 1;
    public static final int BUTTON_SELECT = 2;
    public static final int BUTTON_START  = 3;
    public static final int BUTTON_UP     = 4;
    public static final int BUTTON_DOWN   = 5;
    public static final int BUTTON_LEFT   = 6;
    public static final int BUTTON_RIGHT  = 7;

    /** Live button state, updated asynchronously by the UI. One bit per button. */
    private volatile int buttonState = 0;

    /** Snapshot taken when strobe goes low (or continuously while strobe is high). */
    private int shiftRegister = 0;

    /** True while the CPU is holding the strobe line high. */
    private boolean strobe = false;

    /** Set or clear a single button. Called from the UI thread. */
    public void setButton(int button, boolean pressed) {
        if (pressed) {
            buttonState |= (1 << button);
        } else {
            buttonState &= ~(1 << button);
        }
    }

    /** Handle a CPU write to $4016. Only bit 0 matters for a standard controller. */
    public void write(int value) {
        boolean newStrobe = (value & 0x01) != 0;
        this.strobe = newStrobe;
        if (newStrobe) {
            // While strobe is high, keep reloading the latch from live state.
            shiftRegister = buttonState;
        }
    }

    /**
     * Handle a CPU read of $4016/$4017. Returns the next button bit in bit 0.
     * (The upper bits on real hardware carry open-bus noise; we return 0 there,
     * which is fine for Super Mario Bros. and most games.)
     */
    public int read() {
        if (strobe) {
            // Strobe high: always report the current state of button A.
            return buttonState & 0x01;
        }
        int bit = shiftRegister & 0x01;
        // Shift in a 1 from the top so that after 8 reads we keep returning 1.
        shiftRegister = (shiftRegister >> 1) | 0x80;
        return bit;
    }

    /** Clear all buttons (used on reset). */
    public void reset() {
        buttonState = 0;
        shiftRegister = 0;
        strobe = false;
    }
}
