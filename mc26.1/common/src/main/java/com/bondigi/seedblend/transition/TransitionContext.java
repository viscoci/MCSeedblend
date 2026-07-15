package com.bondigi.seedblend.transition;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Per-generating-chunk transition state, attached to the chunk's {@code Blender}.
 * Immutable; used only by the world-generation thread that owns the chunk.
 */
public final class TransitionContext {
    private final ChunkPos center;
    private final TransitionWeights weights;
    private final DensityFunction oldFinalDensity;
    private final Climate.Sampler oldSampler;
    private final BiomeSource biomeSource;
    private final boolean blendBiomes;

    public TransitionContext(ChunkPos center, TransitionWeights weights,
                             OldGenCache.OldGen oldGen, BiomeSource biomeSource, boolean blendBiomes) {
        this.center = center;
        this.weights = weights;
        this.oldFinalDensity = oldGen.finalDensity();
        this.oldSampler = oldGen.sampler();
        this.biomeSource = biomeSource;
        this.blendBiomes = blendBiomes;
    }

    /** Plain context: default {@code getBlender()} is EMPTY, keeping the old tree inert. */
    private record RawContext(int blockX, int blockY, int blockZ) implements DensityFunction.FunctionContext {
    }

    public double weightAtBlock(int blockX, int blockZ) {
        return weights.weightAt(blockX - center.getMinBlockX(), blockZ - center.getMinBlockZ());
    }

    /** Final density the OLD seed would have produced at this position. */
    public double oldDensity(DensityFunction.FunctionContext ctx) {
        return oldFinalDensity.compute(new RawContext(ctx.blockX(), ctx.blockY(), ctx.blockZ()));
    }

    public double blendDensity(DensityFunction.FunctionContext ctx, double newDensity) {
        double w = weightAtBlock(ctx.blockX(), ctx.blockZ());
        if (w <= 0) {
            return newDensity;
        }
        return w * oldDensity(ctx) + (1.0 - w) * newDensity;
    }

    public boolean blendBiomes() {
        return blendBiomes;
    }

    /**
     * Old-seed biome at quart coordinates. The old climate sampler against the live
     * biome source reproduces the biome layout the old seed would have generated.
     */
    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> oldBiome(int quartX, int quartY, int quartZ) {
        return biomeSource.getNoiseBiome(quartX, quartY, quartZ, oldSampler);
    }

    /**
     * Deterministic dither in (0.2, 0.8): columns switch from new-seed to old-seed
     * biomes at slightly different weights, avoiding a razor-straight biome wall.
     */
    public boolean useOldBiome(int quartX, int quartZ) {
        double w = weightAtBlock(QuartPos.toBlock(quartX) + 2, QuartPos.toBlock(quartZ) + 2);
        if (w <= 0) {
            return false;
        }
        long hash = quartX * 341873128712L + quartZ * 132897987541L;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        double dither = 0.2 + 0.6 * (((hash >>> 24) & 0xFFFF) / 65536.0);
        return w >= dither;
    }

    public double maxWeight() {
        return weights.maxWeight();
    }
}
