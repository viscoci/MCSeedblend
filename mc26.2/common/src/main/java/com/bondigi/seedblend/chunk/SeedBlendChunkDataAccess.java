package com.bondigi.seedblend.chunk;

import org.jetbrains.annotations.Nullable;

/**
 * Mixin-backed duck interface on {@code SerializableChunkData} (26.1). The record does
 * not round-trip unknown NBT, so the epoch and the dimension policy captured at
 * parse/copyOf time ride along to {@code read()}/{@code write()}.
 */
public interface SeedBlendChunkDataAccess {
    long seedblend$getEpoch();

    void seedblend$setEpoch(long epoch);

    boolean seedblend$hasEpoch();

    void seedblend$setPolicy(DimensionBlendPolicy policy);

    @Nullable
    DimensionBlendPolicy seedblend$getPolicy();
}
