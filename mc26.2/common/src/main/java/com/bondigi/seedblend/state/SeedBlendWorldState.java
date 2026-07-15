package com.bondigi.seedblend.state;

import org.jetbrains.annotations.Nullable;

/**
 * Persisted mod state, stored at {@code <world>/seedblend/state.json} (spec §7).
 *
 * <p>Mutable holder mirroring the JSON schema; all mutation happens on the server
 * thread before levels load or from command handlers, never from generation threads.
 */
public final class SeedBlendWorldState {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private int schemaVersion;
    private String worldFingerprint;
    private SeedBlendMode mode;
    /** Epoch-0 seed the world was created with; input to the fingerprint. Never changes. */
    private long originalSeed;
    private long activeEpoch;
    private long activeSeed;
    @Nullable
    private Long previousSeed;
    @Nullable
    private PendingTransaction pendingTransaction;
    private long lastSuccessfulStartupEpoch;

    public SeedBlendWorldState(String worldFingerprint, long originalSeed) {
        this.schemaVersion = CURRENT_SCHEMA_VERSION;
        this.worldFingerprint = worldFingerprint;
        this.mode = SeedBlendMode.CANONICAL_WORLD_SEED;
        this.originalSeed = originalSeed;
        this.activeEpoch = 0L;
        this.activeSeed = originalSeed;
        this.previousSeed = null;
        this.pendingTransaction = null;
        this.lastSuccessfulStartupEpoch = 0L;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String worldFingerprint() {
        return worldFingerprint;
    }

    public SeedBlendMode mode() {
        return mode;
    }

    public long originalSeed() {
        return originalSeed;
    }

    public long activeEpoch() {
        return activeEpoch;
    }

    public long activeSeed() {
        return activeSeed;
    }

    @Nullable
    public Long previousSeed() {
        return previousSeed;
    }

    @Nullable
    public PendingTransaction pendingTransaction() {
        return pendingTransaction;
    }

    public long lastSuccessfulStartupEpoch() {
        return lastSuccessfulStartupEpoch;
    }

    public void stageTransaction(PendingTransaction transaction) {
        this.pendingTransaction = transaction;
    }

    public void cancelTransaction() {
        this.pendingTransaction = null;
    }

    /**
     * Finalize a successfully applied transaction: promote its seed/epoch to active,
     * remember the previous seed, and clear the pending record. Idempotent by design —
     * callers only invoke this after verifying the running server uses the target seed.
     */
    public void finalizeTransaction(PendingTransaction applied) {
        this.previousSeed = this.activeSeed;
        this.activeSeed = applied.targetSeed();
        this.activeEpoch = applied.targetEpoch();
        this.lastSuccessfulStartupEpoch = applied.targetEpoch();
        this.pendingTransaction = null;
    }

    public void markSuccessfulStartup() {
        this.lastSuccessfulStartupEpoch = this.activeEpoch;
    }

    public boolean isValid() {
        return schemaVersion == CURRENT_SCHEMA_VERSION
                && worldFingerprint != null && !worldFingerprint.isEmpty()
                && mode != null
                && activeEpoch >= 0;
    }
}
