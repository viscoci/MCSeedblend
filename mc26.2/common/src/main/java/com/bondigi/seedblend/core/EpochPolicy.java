package com.bondigi.seedblend.core;

/**
 * Epoch interpretation per spec §8. Missing chunk metadata is epoch 0, so worlds that
 * predate SeedBlend need no migration.
 */
public final class EpochPolicy {
    public static final long MISSING_METADATA_EPOCH = 0L;

    public enum Classification {
        /** chunk epoch == active epoch */
        CURRENT,
        /** chunk epoch < active epoch — candidate blending source */
        OLD,
        /** chunk epoch > active epoch — rollback/damage; refuse to load the world */
        FUTURE_INVALID
    }

    private EpochPolicy() {
    }

    public static Classification classify(long chunkEpoch, long activeEpoch) {
        if (chunkEpoch > activeEpoch) {
            return Classification.FUTURE_INVALID;
        }
        return chunkEpoch == activeEpoch ? Classification.CURRENT : Classification.OLD;
    }
}
