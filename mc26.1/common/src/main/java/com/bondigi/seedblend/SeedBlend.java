package com.bondigi.seedblend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent constants and shared initialization.
 */
public final class SeedBlend {
    public static final String MOD_ID = "seedblend";
    public static final String MOD_NAME = "SeedBlend";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private SeedBlend() {
    }

    /**
     * Called once from each loader entrypoint during mod construction.
     * Must not touch any world state — installing the mod alone makes no world changes.
     */
    public static void init() {
        LOGGER.info("[SeedBlend] Initialized (no world changes are made until a reseed is planned and committed)");
    }
}
