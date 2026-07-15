package com.bondigi.seedblend.state;

import java.util.Objects;

/**
 * A staged reseed that has not yet been applied. Written by {@code /seedblend commit},
 * consumed (idempotently) during the next server startup.
 */
public final class PendingTransaction {
    public static final String STATE_STAGED = "STAGED";

    private String transactionId;
    private long targetSeed;
    private long targetEpoch;
    private String createdBy;
    private String state;

    public PendingTransaction(String transactionId, long targetSeed, long targetEpoch, String createdBy, String state) {
        this.transactionId = transactionId;
        this.targetSeed = targetSeed;
        this.targetEpoch = targetEpoch;
        this.createdBy = createdBy;
        this.state = state;
    }

    public String transactionId() {
        return transactionId;
    }

    public long targetSeed() {
        return targetSeed;
    }

    public long targetEpoch() {
        return targetEpoch;
    }

    public String createdBy() {
        return createdBy;
    }

    public String state() {
        return state;
    }

    public boolean isStaged() {
        return STATE_STAGED.equals(state);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PendingTransaction other
                && Objects.equals(transactionId, other.transactionId)
                && targetSeed == other.targetSeed
                && targetEpoch == other.targetEpoch
                && Objects.equals(createdBy, other.createdBy)
                && Objects.equals(state, other.state);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, targetSeed, targetEpoch, createdBy, state);
    }

    @Override
    public String toString() {
        return "PendingTransaction[" + transactionId + " seed=" + targetSeed + " epoch=" + targetEpoch
                + " by=" + createdBy + " state=" + state + "]";
    }
}
