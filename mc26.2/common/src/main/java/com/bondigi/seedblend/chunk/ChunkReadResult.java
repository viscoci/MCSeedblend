package com.bondigi.seedblend.chunk;

/**
 * Outcome of classifying a chunk's serialized NBT at load time (spec §9).
 */
public enum ChunkReadResult {
    /** Load normally (tag may have been augmented with blending/epoch metadata). */
    LOAD,
    /** Old-epoch incomplete chunk: treat as absent and regenerate with the active seed (spec §10). */
    DISCARD_AND_REGENERATE
}
