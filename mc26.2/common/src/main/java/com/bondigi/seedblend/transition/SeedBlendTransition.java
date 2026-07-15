package com.bondigi.seedblend.transition;

import com.bondigi.seedblend.SeedBlend;
import com.bondigi.seedblend.chunk.SeedBlendChunkEpochAccess;
import com.bondigi.seedblend.core.EpochPolicy;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the per-chunk {@link TransitionContext} when the chunk being generated lies
 * within the configured transition range of old-epoch chunks. Called from the
 * {@code Blender.of} mixin on the generating thread; reads only the immutable runtime
 * state and the worldgen region's own chunk cache — no chunk loads, no shared mutation.
 */
public final class SeedBlendTransition {
    /** Dimensions already warned about (unsupported generator), to log once each. */
    private static final Set<String> WARNED_DIMENSIONS = ConcurrentHashMap.newKeySet();

    private SeedBlendTransition() {
    }

    @Nullable
    public static TransitionContext buildContext(WorldGenRegion region) {
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        if (runtime == null || runtime.activeEpoch() == 0) {
            return null;
        }
        ServerLevel level = region.getLevel();
        String dimensionId = level.dimension().identifier().toString();
        if (!runtime.isTransitionDimension(dimensionId)) {
            return null;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            if (WARNED_DIMENSIONS.add(dimensionId)) {
                SeedBlend.LOGGER.warn(
                        "[SeedBlend] Transition blending configured for {} but its generator ({}) is not "
                                + "noise-based — transitions disabled there", dimensionId,
                        generator.getClass().getName());
            }
            return null;
        }

        ChunkPos center = region.getCenter();
        int range = runtime.transitionRange();
        List<TransitionWeights.OldChunk> oldChunks = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkAccess neighbor = region.getChunk(center.x() + dx, center.z() + dz, ChunkStatus.EMPTY, false);
                if (neighbor == null) {
                    continue;
                }
                long epoch = ((SeedBlendChunkEpochAccess) neighbor).seedblend$hasAssignedGenerationEpoch()
                        ? ((SeedBlendChunkEpochAccess) neighbor).seedblend$getGenerationEpoch()
                        : EpochPolicy.MISSING_METADATA_EPOCH;
                // Only completed old-generation chunks anchor a transition.
                if (EpochPolicy.classify(epoch, runtime.activeEpoch()) == EpochPolicy.Classification.OLD
                        && neighbor.getPersistedStatus().isOrAfter(ChunkStatus.FULL)) {
                    oldChunks.add(new TransitionWeights.OldChunk(dx, dz, epoch));
                }
            }
        }

        TransitionWeights weights = TransitionWeights.compute(oldChunks, range);
        if (weights == null) {
            return null;
        }

        OptionalLong oldSeed = runtime.seedForEpoch(weights.nearestEpoch());
        if (oldSeed.isEmpty()) {
            // Schema-1 multi-reseed history gap: fall back to vanilla blending only.
            return null;
        }
        if (oldSeed.getAsLong() == runtime.activeSeed()) {
            return null;
        }

        Holder<NoiseGeneratorSettings> settingsHolder = noiseGenerator.generatorSettings();
        String settingsId = settingsHolder.unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse(dimensionId);
        OldGenCache.OldGen oldGen = OldGenCache.get(
                settingsHolder.value(),
                level.getServer().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),
                settingsId, oldSeed.getAsLong());
        if (oldGen == null) {
            return null;
        }

        SeedBlendRuntime.TRANSITION_CHUNKS.increment();
        TransitionContext context = new TransitionContext(
                center, weights, oldGen, generator.getBiomeSource(), runtime.blendBiomes());

        // Record the max weight on the generating chunk for diagnostics/persistence.
        ChunkAccess centerChunk = region.getChunk(center.x(), center.z(), ChunkStatus.EMPTY, false);
        if (centerChunk != null) {
            ((SeedBlendChunkEpochAccess) centerChunk)
                    .seedblend$setTransitionWeight((int) Math.round(context.maxWeight() * 100));
        }
        return context;
    }
}
