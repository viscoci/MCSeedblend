package com.bondigi.seedblend.core;

import com.bondigi.seedblend.config.SeedBlendConfig;

import org.jetbrains.annotations.Nullable;
import java.util.concurrent.atomic.LongAdder;

/**
 * Publish-once holder for the immutable {@link SeedBlendRuntimeState} plus diagnostic
 * counters (spec §17/§18). The state is written exactly once per server run, before any
 * level loads; generation threads only ever read the volatile reference.
 */
public final class SeedBlendRuntime {
    @Nullable
    private static volatile SeedBlendRuntimeState state;
    @Nullable
    private static volatile SeedBlendConfig config;

    private SeedBlendRuntime() {
    }

    // --- runtime counters (spec §18) ---
    public static final LongAdder CHUNKS_MISSING_METADATA = new LongAdder();
    public static final LongAdder OLD_COMPLETED_CHUNKS = new LongAdder();
    public static final LongAdder SYNTHETIC_BLENDING_INJECTED = new LongAdder();
    public static final LongAdder OLD_INCOMPLETE_DISCARDED = new LongAdder();
    public static final LongAdder NEW_CHUNKS_ASSIGNED_EPOCH = new LongAdder();
    public static final LongAdder MALFORMED_METADATA = new LongAdder();
    public static final LongAdder UNSUPPORTED_GENERATORS = new LongAdder();
    public static final LongAdder FUTURE_EPOCH_REJECTED = new LongAdder();
    public static final LongAdder TRANSITION_CHUNKS = new LongAdder();

    public static void publish(SeedBlendRuntimeState newState) {
        if (state != null) {
            throw new IllegalStateException(
                    "SeedBlend runtime state already published for this server run; the active epoch "
                            + "must not change until the next restart (spec §17)");
        }
        state = newState;
    }

    /** Server shutdown — allows integrated servers to start another world in-process. */
    public static void reset() {
        state = null;
        config = null;
        CHUNKS_MISSING_METADATA.reset();
        OLD_COMPLETED_CHUNKS.reset();
        SYNTHETIC_BLENDING_INJECTED.reset();
        OLD_INCOMPLETE_DISCARDED.reset();
        NEW_CHUNKS_ASSIGNED_EPOCH.reset();
        MALFORMED_METADATA.reset();
        UNSUPPORTED_GENERATORS.reset();
        FUTURE_EPOCH_REJECTED.reset();
        TRANSITION_CHUNKS.reset();
        com.bondigi.seedblend.transition.OldGenCache.clear();
    }

    /** @return the published state, or null when SeedBlend is passive (no reseed ever staged). */
    @Nullable
    public static SeedBlendRuntimeState state() {
        return state;
    }

    public static void setConfig(SeedBlendConfig cfg) {
        config = cfg;
    }

    public static SeedBlendConfig config() {
        SeedBlendConfig cfg = config;
        return cfg != null ? cfg : new SeedBlendConfig();
    }
}
