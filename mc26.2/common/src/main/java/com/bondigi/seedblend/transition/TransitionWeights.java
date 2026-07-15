package com.bondigi.seedblend.transition;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pure math for the normalized transition weight field of one generating chunk.
 *
 * <p>Weight semantics: {@code 1.0} at an old-chunk boundary (terrain computes ≈ the old
 * seed, so the seam is continuous by construction) fading smoothly to {@code 0.0} at
 * {@code rangeChunks} chunks from the nearest old chunk (pure new-seed terrain).
 * Distance is euclidean, block-scale, measured to the nearest edge of the nearest
 * old-epoch chunk; the fade curve is smoothstep for C1-continuous terrain gradients.
 */
public final class TransitionWeights {
    /** Grid covers x/z block offsets 0..16 inclusive (noise cell corners touch +16). */
    public static final int GRID = 17;

    private final double[] weights;
    private final long nearestEpoch;
    private final double maxWeight;

    private TransitionWeights(double[] weights, long nearestEpoch, double maxWeight) {
        this.weights = weights;
        this.nearestEpoch = nearestEpoch;
        this.maxWeight = maxWeight;
    }

    /** An old-epoch chunk near the generating chunk, in chunk-offset coordinates. */
    public record OldChunk(int offsetX, int offsetZ, long epoch) {
    }

    /**
     * @param oldChunks   old-epoch chunks within {@code rangeChunks}, as chunk offsets
     *                    relative to the generating chunk (e.g. (-1, 0) = west neighbor)
     * @param rangeChunks transition width in chunks (>= 1)
     * @return the weight field, or null when no old chunk contributes any weight
     */
    @Nullable
    public static TransitionWeights compute(List<OldChunk> oldChunks, int rangeChunks) {
        if (oldChunks.isEmpty()) {
            return null;
        }
        double rangeBlocks = rangeChunks * 16.0;
        double[] weights = new double[GRID * GRID];
        double maxWeight = 0;
        double bestDist = Double.MAX_VALUE;
        long nearestEpoch = -1;

        for (OldChunk old : oldChunks) {
            double chunkDist = rectDistance(8, 8, old);
            if (chunkDist < bestDist) {
                bestDist = chunkDist;
                nearestEpoch = old.epoch();
            }
        }

        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                double dist = Double.MAX_VALUE;
                for (OldChunk old : oldChunks) {
                    dist = Math.min(dist, rectDistance(x, z, old));
                }
                double t = 1.0 - Math.min(1.0, dist / rangeBlocks);
                double w = smoothstep(t);
                weights[x * GRID + z] = w;
                maxWeight = Math.max(maxWeight, w);
            }
        }
        return maxWeight <= 0 ? null : new TransitionWeights(weights, nearestEpoch, maxWeight);
    }

    /** Euclidean distance in blocks from local block (x, z) to the old chunk's rectangle. */
    private static double rectDistance(double x, double z, OldChunk old) {
        double minX = old.offsetX() * 16.0;
        double minZ = old.offsetZ() * 16.0;
        double dx = Math.max(0, Math.max(minX - x, x - (minX + 16.0)));
        double dz = Math.max(0, Math.max(minZ - z, z - (minZ + 16.0)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double smoothstep(double t) {
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3.0 - 2.0 * t);
    }

    /** Weight at a local block offset within the generating chunk; clamps to the grid. */
    public double weightAt(int localX, int localZ) {
        int x = Math.max(0, Math.min(GRID - 1, localX));
        int z = Math.max(0, Math.min(GRID - 1, localZ));
        return weights[x * GRID + z];
    }

    /** Epoch of the nearest old chunk — decides which historical seed to blend toward. */
    public long nearestEpoch() {
        return nearestEpoch;
    }

    public double maxWeight() {
        return maxWeight;
    }
}
