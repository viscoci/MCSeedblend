package com.bondigi.seedblend.chunk;

/**
 * Per-dimension blending decision plus the vertical bounds used when synthesizing
 * {@code blending_data}. Bounds always come from the dimension's actual section range —
 * never hard-coded Overworld values (spec §4.1).
 *
 * @param dimensionId        e.g. {@code "minecraft:overworld"}
 * @param blendingSupported  whether synthetic blending data may be injected
 * @param minSection         the dimension's minimum section index (inclusive)
 * @param maxSectionExclusive the dimension's maximum section index (exclusive)
 */
public record DimensionBlendPolicy(
        String dimensionId,
        boolean blendingSupported,
        int minSection,
        int maxSectionExclusive
) {
}
