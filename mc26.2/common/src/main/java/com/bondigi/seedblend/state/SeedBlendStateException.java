package com.bondigi.seedblend.state;

/**
 * Fatal state problem — startup must fail closed (spec §19).
 */
public class SeedBlendStateException extends RuntimeException {
    public SeedBlendStateException(String message) {
        super(message);
    }

    public SeedBlendStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
