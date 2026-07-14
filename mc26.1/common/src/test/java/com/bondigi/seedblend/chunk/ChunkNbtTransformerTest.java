package com.bondigi.seedblend.chunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChunkNbtTransformerTest {
    private static final DimensionBlendPolicy OVERWORLD =
            new DimensionBlendPolicy("minecraft:overworld", true, -4, 20);
    private static final DimensionBlendPolicy NETHER =
            new DimensionBlendPolicy("minecraft:the_nether", false, 0, 8);

    private CompoundTag chunk(String status) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ChunkNbtKeys.STATUS, status);
        return tag;
    }

    private CompoundTag withEpoch(CompoundTag tag, long epoch) {
        tag.put(ChunkNbtKeys.SEEDBLEND, ChunkNbtTransformer.createMetadata(epoch));
        return tag;
    }

    @BeforeEach
    void resetRuntime() {
        com.bondigi.seedblend.core.SeedBlendRuntime.reset();
    }

    // --- missing metadata → epoch 0 (spec §8) ---

    @Test
    void missingMetadataIsEpochZero() {
        assertEquals(0L, ChunkNbtTransformer.readEpoch(chunk("minecraft:full")));
    }

    // --- future epoch rejection ---

    @Test
    void futureEpochThrows() {
        CompoundTag tag = withEpoch(chunk("minecraft:full"), 5);
        assertThrows(SeedBlendEpochRollbackException.class,
                () -> ChunkNbtTransformer.process(tag, OVERWORLD, 2));
    }

    // --- current epoch chunks untouched ---

    @Test
    void currentEpochChunkNotModified() {
        CompoundTag tag = withEpoch(chunk("minecraft:full"), 1);
        assertEquals(ChunkReadResult.LOAD, ChunkNbtTransformer.process(tag, OVERWORLD, 1));
        assertFalse(tag.contains(ChunkNbtKeys.BLENDING_DATA), "current chunks never get synthetic blending");
        assertEquals(1L, tag.getCompoundOrEmpty(ChunkNbtKeys.SEEDBLEND).getLongOr(ChunkNbtKeys.GENERATION_EPOCH, -1));
    }

    // --- old completed chunk becomes blending source (spec §9) ---

    @Test
    void oldCompleteChunkGetsSyntheticBlendingData() {
        CompoundTag tag = chunk("minecraft:full"); // pre-SeedBlend chunk, epoch 0
        assertEquals(ChunkReadResult.LOAD, ChunkNbtTransformer.process(tag, OVERWORLD, 1));

        CompoundTag blending = tag.getCompoundOrEmpty(ChunkNbtKeys.BLENDING_DATA);
        assertEquals(-4, blending.getIntOr(ChunkNbtKeys.MIN_SECTION, 99), "dimension-true min section");
        assertEquals(20, blending.getIntOr(ChunkNbtKeys.MAX_SECTION, 99), "dimension-true exclusive max section");

        // Epoch metadata written, preserving the ORIGINAL epoch — epochs are immutable.
        CompoundTag meta = tag.getCompoundOrEmpty(ChunkNbtKeys.SEEDBLEND);
        assertEquals(0L, meta.getLongOr(ChunkNbtKeys.GENERATION_EPOCH, -1));
        assertEquals(1, meta.getIntOr(ChunkNbtKeys.SCHEMA, -1));
    }

    @Test
    void oldChunkFromEarlierEpochAlsoOldUnderLaterEpochs() {
        // Multi-reseed: epoch 1 chunk is old when epoch 2 is active.
        CompoundTag tag = withEpoch(chunk("minecraft:full"), 1);
        assertEquals(ChunkReadResult.LOAD, ChunkNbtTransformer.process(tag, OVERWORLD, 2));
        assertTrue(tag.contains(ChunkNbtKeys.BLENDING_DATA));
        assertEquals(1L, tag.getCompoundOrEmpty(ChunkNbtKeys.SEEDBLEND).getLongOr(ChunkNbtKeys.GENERATION_EPOCH, -1));
    }

    // --- existing blending data preserved (spec §9) ---

    @Test
    void existingValidBlendingDataPreserved() {
        CompoundTag tag = chunk("minecraft:full");
        CompoundTag existing = new CompoundTag();
        existing.putInt(ChunkNbtKeys.MIN_SECTION, -4);
        existing.putInt(ChunkNbtKeys.MAX_SECTION, 20);
        ListTag heights = new ListTag();
        for (int i = 0; i < 4; i++) {
            heights.add(DoubleTag.valueOf(63.5));
        }
        existing.put("heights", heights);
        tag.put(ChunkNbtKeys.BLENDING_DATA, existing);

        ChunkNbtTransformer.process(tag, OVERWORLD, 1);

        CompoundTag after = tag.getCompoundOrEmpty(ChunkNbtKeys.BLENDING_DATA);
        assertTrue(after.contains("heights"), "calculated height arrays must be preserved");
        assertEquals(4, after.getListOrEmpty("heights").size());
    }

    @Test
    void malformedBlendingDataReplaced() {
        CompoundTag tag = chunk("minecraft:full");
        tag.put(ChunkNbtKeys.BLENDING_DATA, StringTag.valueOf("nonsense"));

        ChunkNbtTransformer.process(tag, OVERWORLD, 1);

        CompoundTag after = tag.getCompoundOrEmpty(ChunkNbtKeys.BLENDING_DATA);
        assertEquals(-4, after.getIntOr(ChunkNbtKeys.MIN_SECTION, 99));
        assertEquals(20, after.getIntOr(ChunkNbtKeys.MAX_SECTION, 99));
    }

    @Test
    void invertedBoundsBlendingDataReplaced() {
        CompoundTag tag = chunk("minecraft:full");
        CompoundTag bad = new CompoundTag();
        bad.putInt(ChunkNbtKeys.MIN_SECTION, 20);
        bad.putInt(ChunkNbtKeys.MAX_SECTION, -4);
        tag.put(ChunkNbtKeys.BLENDING_DATA, bad);

        ChunkNbtTransformer.process(tag, OVERWORLD, 1);
        assertTrue(BlendingDataFactory.isValid(tag.get(ChunkNbtKeys.BLENDING_DATA)));
    }

    // --- unsupported dimensions never receive synthetic data (spec §6) ---

    @Test
    void unsupportedDimensionNeverGetsSyntheticBlending() {
        CompoundTag tag = chunk("minecraft:full");
        assertEquals(ChunkReadResult.LOAD, ChunkNbtTransformer.process(tag, NETHER, 1));
        assertFalse(tag.contains(ChunkNbtKeys.BLENDING_DATA));
    }

    // --- old incomplete chunks discarded, never blended (spec §10) ---

    @Test
    void oldIncompleteChunksDiscarded() {
        // Every pre-FULL status in the 26.1 generation pipeline.
        String[] incomplete = {
                "minecraft:empty", "minecraft:structure_starts", "minecraft:structure_references",
                "minecraft:biomes", "minecraft:noise", "minecraft:surface", "minecraft:carvers",
                "minecraft:features", "minecraft:initialize_light", "minecraft:light", "minecraft:spawn"
        };
        for (String status : incomplete) {
            CompoundTag tag = chunk(status);
            assertEquals(ChunkReadResult.DISCARD_AND_REGENERATE,
                    ChunkNbtTransformer.process(tag, OVERWORLD, 1),
                    "status " + status + " must be discarded");
            assertFalse(tag.contains(ChunkNbtKeys.BLENDING_DATA),
                    "status " + status + " must never get blending_data");
        }
    }

    @Test
    void legacyUnnamespacedFullStatusRecognized() {
        CompoundTag tag = chunk("full");
        assertEquals(ChunkReadResult.LOAD, ChunkNbtTransformer.process(tag, OVERWORLD, 1));
        assertTrue(tag.contains(ChunkNbtKeys.BLENDING_DATA));
    }

    @Test
    void currentEpochIncompleteChunkLoadsNormally() {
        // In-progress chunk from the CURRENT epoch is normal proto-chunk state.
        CompoundTag tag = withEpoch(chunk("minecraft:noise"), 1);
        assertEquals(ChunkReadResult.LOAD, ChunkNbtTransformer.process(tag, OVERWORLD, 1));
    }

    // --- malformed seedblend metadata (spec §13 verify counters) ---

    @Test
    void malformedMetadataTreatedAsEpochZero() {
        CompoundTag tag = chunk("minecraft:full");
        tag.putString(ChunkNbtKeys.SEEDBLEND, "not-a-compound");
        assertEquals(0L, ChunkNbtTransformer.readEpoch(tag));

        CompoundTag tag2 = chunk("minecraft:full");
        CompoundTag meta = new CompoundTag();
        meta.putString(ChunkNbtKeys.GENERATION_EPOCH, "NaN");
        tag2.put(ChunkNbtKeys.SEEDBLEND, meta);
        assertEquals(0L, ChunkNbtTransformer.readEpoch(tag2));

        // process() repairs the malformed compound with epoch 0.
        ChunkNbtTransformer.process(tag2, OVERWORLD, 0);
        assertEquals(0L, tag2.getCompoundOrEmpty(ChunkNbtKeys.SEEDBLEND).getLongOr(ChunkNbtKeys.GENERATION_EPOCH, -1));
    }
}
