package com.nesemu.cartridge;

/** Thrown when a ROM file cannot be parsed or is not supported. */
public class InvalidRomException extends RuntimeException {
    public InvalidRomException(String message) {
        super(message);
    }

    public InvalidRomException(String message, Throwable cause) {
        super(message, cause);
    }
}
