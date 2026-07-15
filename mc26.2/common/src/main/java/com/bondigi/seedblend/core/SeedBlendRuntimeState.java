package com.bondigi.seedblend.core;

import com.bondigi.seedblend.state.SeedBlendMode;

import java.util.Set;

/**
 * Immutable per-run context (spec §17), published once before levels begin loading and
 * safely readable from world-generation and chunk-I/O threads. Dimensions are tracked by
 * id string (e.g. {@code "minecraft:overworld"}) so this record stays independent of
 * registry lifecycles.
 */
public record SeedBlendRuntimeState(
        long activeSeed,
        long activeEpoch,
        SeedBlendMode mode,
        Set<String> blendingDimensions
) {
    public SeedBlendRuntimeState {
        blendingDimensions = Set.copyOf(blendingDimensions);
    }

    public boolean isBlendingDimension(String dimensionId) {
        return blendingDimensions.contains(dimensionId);
    }
}
