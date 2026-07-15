package com.bondigi.seedblend.chunk;

/**
 * Mixin-backed duck interface on {@code ChunkAccess} (spec §11). New in-memory chunks
 * have no assigned epoch until deserialization stamps one or serialization assigns the
 * active epoch.
 */
public interface SeedBlendChunkEpochAccess {
    long seedblend$getGenerationEpoch();

    void seedblend$setGenerationEpoch(long epoch);

    boolean seedblend$hasAssignedGenerationEpoch();
}
