package com.bondigi.seedblend.chunk;

/**
 * NBT keys used by SeedBlend and the vanilla chunk serializer (1.21.1: keys owned by
 * {@code net.minecraft.world.level.chunk.storage.ChunkSerializer}).
 */
public final class ChunkNbtKeys {
    // SeedBlend namespaced compound (spec §8)
    public static final String SEEDBLEND = "seedblend";
    public static final String SCHEMA = "schema";
    public static final String GENERATION_EPOCH = "generation_epoch";
    public static final int CURRENT_SCHEMA = 1;

    // Vanilla
    public static final String BLENDING_DATA = "blending_data";
    public static final String MIN_SECTION = "min_section";
    public static final String MAX_SECTION = "max_section";
    public static final String STATUS = "Status";

    private ChunkNbtKeys() {
    }
}
