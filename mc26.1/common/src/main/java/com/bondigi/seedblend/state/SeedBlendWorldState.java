package com.bondigi.seedblend.state;

import org.jetbrains.annotations.Nullable;

/**
 * Persisted mod state, stored at {@code <world>/seedblend/state.json} (spec §7).
 *
 * <p>Mutable holder mirroring the JSON schema; all mutation happens on the server
 * thread before levels load or from command handlers, never from generation threads.
 */
public final class SeedBlendWorldState {
    public static final int CURRENT_SCHEMA_VERSION = 2;

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
    /**
     * Schema 2: every epoch's seed, keyed by epoch (Gson maps keys as strings). Needed
     * by transition blending so a boundary against an epoch-N chunk blends toward the
     * terrain seed N actually used. Append-only.
     */
    @Nullable
    private java.util.Map<String, Long> seedHistory;

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
        this.seedHistory = new java.util.TreeMap<>();
        this.seedHistory.put("0", originalSeed);
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
        seedHistory().put(Long.toString(applied.targetEpoch()), applied.targetSeed());
    }

    /**
     * Epoch → seed map, upgrading schema-1 files in place: epoch 0 and the active epoch
     * are always reconstructible; the previous epoch when previousSeed was recorded.
     * Older intermediate epochs from schema-1 multi-reseed histories are unknowable —
     * transition blending falls back to vanilla blending against those chunks.
     */
    public java.util.Map<String, Long> seedHistory() {
        if (seedHistory == null) {
            seedHistory = new java.util.TreeMap<>();
        }
        seedHistory.putIfAbsent("0", originalSeed);
        seedHistory.putIfAbsent(Long.toString(activeEpoch), activeSeed);
        if (previousSeed != null && activeEpoch > 0) {
            seedHistory.putIfAbsent(Long.toString(activeEpoch - 1), previousSeed);
        }
        if (schemaVersion < CURRENT_SCHEMA_VERSION) {
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }
        return seedHistory;
    }

    public java.util.OptionalLong seedForEpoch(long epoch) {
        Long seed = seedHistory().get(Long.toString(epoch));
        return seed != null ? java.util.OptionalLong.of(seed) : java.util.OptionalLong.empty();
    }

    public void markSuccessfulStartup() {
        this.lastSuccessfulStartupEpoch = this.activeEpoch;
    }

    public boolean isValid() {
        return schemaVersion >= 1 && schemaVersion <= CURRENT_SCHEMA_VERSION
                && worldFingerprint != null && !worldFingerprint.isEmpty()
                && mode != null
                && activeEpoch >= 0;
    }
}
