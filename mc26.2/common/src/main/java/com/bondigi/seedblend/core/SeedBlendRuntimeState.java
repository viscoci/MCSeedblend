package com.bondigi.seedblend.core;

import com.bondigi.seedblend.state.SeedBlendMode;

import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Immutable per-run context (spec §17), published once before levels begin loading and
 * safely readable from world-generation and chunk-I/O threads. Dimensions are tracked by
 * id string (e.g. {@code "minecraft:overworld"}) so this record stays independent of
 * registry lifecycles.
 *
 * @param seedHistory          epoch → seed for every known epoch (transition blending)
 * @param transitionDimensions dimensions with SeedBlend transition blending enabled
 * @param transitionRange      transition width in chunks (1..7)
 * @param blendBiomes          whether biome selection dithers across the transition
 */
public record SeedBlendRuntimeState(
        long activeSeed,
        long activeEpoch,
        SeedBlendMode mode,
        Set<String> blendingDimensions,
        Map<Long, Long> seedHistory,
        Set<String> transitionDimensions,
        int transitionRange,
        boolean blendBiomes
) {
    public SeedBlendRuntimeState {
        blendingDimensions = Set.copyOf(blendingDimensions);
        seedHistory = Map.copyOf(seedHistory);
        transitionDimensions = Set.copyOf(transitionDimensions);
    }

    public boolean isBlendingDimension(String dimensionId) {
        return blendingDimensions.contains(dimensionId);
    }

    public boolean isTransitionDimension(String dimensionId) {
        return transitionDimensions.contains(dimensionId);
    }

    public OptionalLong seedForEpoch(long epoch) {
        Long seed = seedHistory.get(epoch);
        return seed != null ? OptionalLong.of(seed) : OptionalLong.empty();
    }
}
