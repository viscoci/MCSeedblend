package com.bondigi.seedblend.chunk;

/**
 * A serialized chunk carries an epoch newer than the active world epoch — the state
 * file was rolled back or damaged. The world must refuse to load (spec §8, §19).
 */
public class SeedBlendEpochRollbackException extends RuntimeException {
    public SeedBlendEpochRollbackException(long chunkEpoch, long activeEpoch) {
        super("Chunk generation epoch " + chunkEpoch + " is newer than the active world epoch "
                + activeEpoch + ". SeedBlend state was rolled back or is damaged; refusing to "
                + "load. Restore a matching world backup (world data and the seedblend/state.json "
                + "must come from the same backup).");
    }
}
