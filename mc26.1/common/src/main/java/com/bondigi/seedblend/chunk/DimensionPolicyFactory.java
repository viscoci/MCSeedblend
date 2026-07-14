package com.bondigi.seedblend.chunk;

import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * Builds the per-dimension blending decision from a live level (spec §6): a dimension is
 * blendable only when configured AND its generator is vanilla noise-based (flat, debug,
 * and custom generators that bypass the vanilla {@code Blender} are excluded).
 */
public final class DimensionPolicyFactory {
    private DimensionPolicyFactory() {
    }

    public static DimensionBlendPolicy of(ServerLevel level) {
        String id = level.dimension().identifier().toString();
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        boolean supported = runtime != null
                && runtime.isBlendingDimension(id)
                && generatorCompatible(level.getChunkSource().getGenerator());
        // 26.1: getMaxSectionY() is inclusive; BlendingData bounds use an exclusive max.
        return new DimensionBlendPolicy(id, supported, level.getMinSectionY(), level.getMaxSectionY() + 1);
    }

    private static boolean generatorCompatible(ChunkGenerator generator) {
        if (generator instanceof NoiseBasedChunkGenerator) {
            return true;
        }
        if (SeedBlendRuntime.config().allowCustomNoiseGenerators) {
            return true;
        }
        SeedBlendRuntime.UNSUPPORTED_GENERATORS.increment();
        return false;
    }
}
