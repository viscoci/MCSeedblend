package com.bondigi.seedblend.transition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransitionWeightsTest {
    private static final TransitionWeights.OldChunk WEST = new TransitionWeights.OldChunk(-1, 0, 0);

    @Test
    void noOldChunksMeansNoField() {
        assertNull(TransitionWeights.compute(List.of(), 4));
    }

    @Test
    void weightIsOneAtOldBoundaryAndZeroAtRangeEnd() {
        TransitionWeights weights = TransitionWeights.compute(List.of(WEST), 4);
        assertNotNull(weights);
        // Local x=0 touches the west neighbor's edge → full old-seed terrain (seamless).
        assertEquals(1.0, weights.weightAt(0, 8), 1e-9);
        // Range is 4 chunks = 64 blocks; the far side of the grid is still inside range.
        assertTrue(weights.weightAt(16, 8) > 0);
        assertTrue(weights.weightAt(16, 8) < weights.weightAt(0, 8));
    }

    @Test
    void weightDecreasesMonotonicallyAwayFromBoundary() {
        TransitionWeights weights = TransitionWeights.compute(List.of(WEST), 2);
        double previous = Double.MAX_VALUE;
        for (int x = 0; x <= 16; x++) {
            double w = weights.weightAt(x, 8);
            assertTrue(w <= previous + 1e-12, "weight must not increase away from the boundary at x=" + x);
            previous = w;
        }
    }

    @Test
    void distantOldChunkContributesNothing() {
        // Old chunk 5 chunks away, range 2 → nearest edge is 64 blocks, range is 32 blocks.
        TransitionWeights weights = TransitionWeights.compute(
                List.of(new TransitionWeights.OldChunk(5, 0, 0)), 2);
        assertNull(weights, "chunks beyond the range produce no transition field");
    }

    @Test
    void nearestChunkEpochWins() {
        // Epoch-1 chunk adjacent west; epoch-0 chunk three chunks east.
        TransitionWeights weights = TransitionWeights.compute(List.of(
                new TransitionWeights.OldChunk(-1, 0, 1),
                new TransitionWeights.OldChunk(3, 0, 0)), 4);
        assertNotNull(weights);
        assertEquals(1, weights.nearestEpoch());
    }

    @Test
    void diagonalNeighborUsesEuclideanDistance() {
        TransitionWeights weights = TransitionWeights.compute(
                List.of(new TransitionWeights.OldChunk(-1, -1, 0)), 4);
        assertNotNull(weights);
        // Corner column (0,0) touches the diagonal chunk's corner: distance 0 → weight 1.
        assertEquals(1.0, weights.weightAt(0, 0), 1e-9);
        // Straight-line distance to (16,16) is sqrt(2)*16 ≈ 22.6 blocks < 64 → some weight.
        double corner = weights.weightAt(16, 16);
        double edge = weights.weightAt(16, 0);
        assertTrue(corner < weights.weightAt(0, 0));
        assertTrue(edge >= corner, "moving diagonally away loses weight fastest");
    }

    @Test
    void surroundedChunkIsFullyOldWeighted() {
        List<TransitionWeights.OldChunk> ring = List.of(
                new TransitionWeights.OldChunk(-1, -1, 0), new TransitionWeights.OldChunk(-1, 0, 0),
                new TransitionWeights.OldChunk(-1, 1, 0), new TransitionWeights.OldChunk(0, -1, 0),
                new TransitionWeights.OldChunk(0, 1, 0), new TransitionWeights.OldChunk(1, -1, 0),
                new TransitionWeights.OldChunk(1, 0, 0), new TransitionWeights.OldChunk(1, 1, 0));
        TransitionWeights weights = TransitionWeights.compute(ring, 4);
        // Enclosed ungenerated hole: edges touch old terrain (w=1), the centre is at
        // most 8 blocks from an old chunk → near-full old weighting everywhere.
        assertEquals(1.0, weights.weightAt(0, 8), 1e-9);
        assertEquals(1.0, weights.weightAt(16, 8), 1e-9);
        for (int x = 0; x <= 16; x += 8) {
            for (int z = 0; z <= 16; z += 8) {
                assertTrue(weights.weightAt(x, z) > 0.9,
                        "hole chunk column (" + x + "," + z + ") must be nearly fully old-weighted");
            }
        }
        assertEquals(1.0, weights.maxWeight(), 1e-9);
    }
}
