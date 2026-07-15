package com.bondigi.seedblend.transition;

import com.bondigi.seedblend.SeedBlend;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache of old-seed generation state per (noise settings, seed). Entries are immutable
 * after construction and safely shared across world-generation threads; the cache is
 * cleared on server stop.
 */
public final class OldGenCache {
    /** Old-seed generator pieces needed by transition blending. */
    public record OldGen(RandomState randomState, DensityFunction finalDensity, Climate.Sampler sampler) {
    }

    private record Key(String settingsId, long seed) {
    }

    private static final Map<Key, OldGen> CACHE = new ConcurrentHashMap<>();

    private OldGenCache() {
    }

    @Nullable
    public static OldGen get(NoiseGeneratorSettings settings,
                             HolderGetter<NormalNoise.NoiseParameters> noiseParameters,
                             String settingsId, long seed) {
        return CACHE.computeIfAbsent(new Key(settingsId, seed), key -> {
            try {
                RandomState randomState = RandomState.create(settings, noiseParameters, key.seed());
                // Raw compute of the router's final density: called through a plain
                // FunctionContext, whose default getBlender() is EMPTY — so the tree's
                // own blend_density node is an identity pass and cannot recurse into
                // the live blender.
                return new OldGen(randomState, randomState.router().finalDensity(), randomState.sampler());
            } catch (Exception e) {
                SeedBlend.LOGGER.warn("[SeedBlend] Could not build old-seed generator state for {} seed {}: {}",
                        key.settingsId(), key.seed(), e.toString());
                return null;
            }
        });
    }

    public static void clear() {
        CACHE.clear();
    }
}
