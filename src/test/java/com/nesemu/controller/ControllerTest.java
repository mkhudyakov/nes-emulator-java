package com.nesemu.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControllerTest {

    private Controller pad;

    @BeforeEach
    void setUp() {
        pad = new Controller();
    }

    /** Latch the current button state by pulsing the strobe high then low. */
    private void strobe() {
        pad.write(1);
        pad.write(0);
    }

    @Test
    void readsButtonsInOrderAfterStrobe() {
        // Press A and Right. Read order is A,B,Select,Start,Up,Down,Left,Right.
        pad.setButton(Controller.BUTTON_A, true);
        pad.setButton(Controller.BUTTON_RIGHT, true);
        strobe();

        assertEquals(1, pad.read() & 1); // A
        assertEquals(0, pad.read() & 1); // B
        assertEquals(0, pad.read() & 1); // Select
        assertEquals(0, pad.read() & 1); // Start
        assertEquals(0, pad.read() & 1); // Up
        assertEquals(0, pad.read() & 1); // Down
        assertEquals(0, pad.read() & 1); // Left
        assertEquals(1, pad.read() & 1); // Right
    }

    @Test
    void readsReturnOneAfterEightShifts() {
        pad.setButton(Controller.BUTTON_A, true);
        strobe();
        for (int i = 0; i < 8; i++) {
            pad.read();
        }
        // After the 8 real buttons, an official controller returns 1s.
        assertEquals(1, pad.read() & 1);
        assertEquals(1, pad.read() & 1);
    }

    @Test
    void strobeHighReloadsContinuously() {
        pad.setButton(Controller.BUTTON_A, true);
        // While strobe is held high, reads always reflect the first button (A).
        pad.write(1);
        assertEquals(1, pad.read() & 1);
        assertEquals(1, pad.read() & 1);
    }

    @Test
    void releasedButtonReadsZero() {
        pad.setButton(Controller.BUTTON_A, true);
        pad.setButton(Controller.BUTTON_A, false);
        strobe();
        assertEquals(0, pad.read() & 1);
    }
}
