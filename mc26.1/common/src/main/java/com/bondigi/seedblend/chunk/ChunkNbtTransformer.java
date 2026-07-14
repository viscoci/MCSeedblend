package com.bondigi.seedblend.chunk;

import com.bondigi.seedblend.SeedBlend;
import com.bondigi.seedblend.core.EpochPolicy;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Pure transformation of serialized chunk NBT at load time (spec §9). Operates on the
 * chunk root compound before vanilla decodes it via {@code SerializableChunkData.parse}.
 * Thread-safe: no shared mutable state beyond {@link SeedBlendRuntime} counters; the tag
 * instance is owned by the calling I/O thread.
 */
public final class ChunkNbtTransformer {
    /**
     * The completed-status threshold. Verified against the 26.1 chunk serializer
     * (see docs/source-audit-26.1.md): only status {@code minecraft:full} has
     * ChunkType.LEVELCHUNK; every earlier status is generation-incomplete.
     */
    private static final String FULL_STATUS = "minecraft:full";

    private ChunkNbtTransformer() {
    }

    /**
     * Classify and (when needed) augment a chunk tag that was just read from disk.
     *
     * @throws SeedBlendEpochRollbackException when the chunk's epoch is newer than the
     *                                         active epoch — the caller must abort.
     */
    public static ChunkReadResult process(CompoundTag chunkTag, DimensionBlendPolicy dimension, long activeEpoch) {
        long chunkEpoch = readEpoch(chunkTag);
        EpochPolicy.Classification classification = EpochPolicy.classify(chunkEpoch, activeEpoch);

        switch (classification) {
            case FUTURE_INVALID -> {
                SeedBlendRuntime.FUTURE_EPOCH_REJECTED.increment();
                throw new SeedBlendEpochRollbackException(chunkEpoch, activeEpoch);
            }
            case OLD -> {
                if (!isStatusComplete(chunkTag)) {
                    if (SeedBlendRuntime.config().discardIncompleteOldChunks) {
                        SeedBlendRuntime.OLD_INCOMPLETE_DISCARDED.increment();
                        return ChunkReadResult.DISCARD_AND_REGENERATE;
                    }
                    // Discard disabled by config: never mark incomplete chunks for blending.
                } else {
                    SeedBlendRuntime.OLD_COMPLETED_CHUNKS.increment();
                    if (dimension.blendingSupported()) {
                        ensureBlendingData(chunkTag, dimension);
                    }
                }
            }
            case CURRENT -> {
                // Nothing to do.
            }
        }

        ensureSeedBlendMetadata(chunkTag, chunkEpoch);
        return ChunkReadResult.LOAD;
    }

    /**
     * Epoch from the {@code seedblend} compound; missing metadata is epoch 0 (spec §8),
     * malformed metadata warns, counts, and is treated as missing.
     */
    public static long readEpoch(CompoundTag chunkTag) {
        OptionalLong epoch = tryReadEpoch(chunkTag);
        return epoch.orElse(EpochPolicy.MISSING_METADATA_EPOCH);
    }

    private static OptionalLong tryReadEpoch(CompoundTag chunkTag) {
        if (!chunkTag.contains(ChunkNbtKeys.SEEDBLEND)) {
            SeedBlendRuntime.CHUNKS_MISSING_METADATA.increment();
            return OptionalLong.empty();
        }
        Optional<CompoundTag> seedblend = chunkTag.getCompound(ChunkNbtKeys.SEEDBLEND);
        if (seedblend.isEmpty()) {
            malformed("seedblend tag is not a compound");
            return OptionalLong.empty();
        }
        Optional<Long> epoch = seedblend.get().getLong(ChunkNbtKeys.GENERATION_EPOCH);
        if (epoch.isEmpty()) {
            malformed("generation_epoch missing or non-numeric");
            return OptionalLong.empty();
        }
        if (epoch.get() < 0) {
            malformed("negative generation_epoch " + epoch.get());
            return OptionalLong.empty();
        }
        return OptionalLong.of(epoch.get());
    }

    private static void malformed(String reason) {
        SeedBlendRuntime.MALFORMED_METADATA.increment();
        SeedBlend.LOGGER.warn("[SeedBlend] Malformed chunk metadata ({}) — treating as epoch 0", reason);
    }

    /**
     * Whether the serialized status is the completed status. In 26.1 the serializer
     * writes the status registry id; only FULL round-trips as a LevelChunk.
     */
    public static boolean isStatusComplete(CompoundTag chunkTag) {
        String status = chunkTag.getStringOr(ChunkNbtKeys.STATUS, "");
        if (status.isEmpty()) {
            return false;
        }
        // Registry ids may serialize without the default namespace in older data.
        String normalized = status.indexOf(':') >= 0 ? status : "minecraft:" + status;
        return FULL_STATUS.equals(normalized);
    }

    /**
     * Synthesize {@code blending_data} bounds when absent (spec §9): preserve any valid
     * existing compound (vanilla upgrade data, height arrays); replace only undecodable
     * data, with a warning.
     */
    public static void ensureBlendingData(CompoundTag chunkTag, DimensionBlendPolicy dimension) {
        if (chunkTag.contains(ChunkNbtKeys.BLENDING_DATA)) {
            Tag existing = chunkTag.get(ChunkNbtKeys.BLENDING_DATA);
            if (BlendingDataFactory.isValid(existing)) {
                return;
            }
            SeedBlend.LOGGER.warn("[SeedBlend] Replacing undecodable blending_data in {} chunk",
                    dimension.dimensionId());
        }
        chunkTag.put(ChunkNbtKeys.BLENDING_DATA,
                BlendingDataFactory.createTag(dimension.minSection(), dimension.maxSectionExclusive()));
        SeedBlendRuntime.SYNTHETIC_BLENDING_INJECTED.increment();
    }

    /**
     * Ensure the chunk tag carries SeedBlend metadata with the given epoch. Existing
     * epochs are immutable — this only fills in absent/malformed metadata (spec §4.3).
     */
    public static void ensureSeedBlendMetadata(CompoundTag chunkTag, long epoch) {
        Optional<CompoundTag> existing = chunkTag.getCompound(ChunkNbtKeys.SEEDBLEND);
        if (existing.isPresent()) {
            Optional<Long> current = existing.get().getLong(ChunkNbtKeys.GENERATION_EPOCH);
            if (current.isPresent() && current.get() >= 0) {
                return;
            }
        }
        chunkTag.put(ChunkNbtKeys.SEEDBLEND, createMetadata(epoch));
    }

    public static CompoundTag createMetadata(long epoch) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(ChunkNbtKeys.SCHEMA, ChunkNbtKeys.CURRENT_SCHEMA);
        tag.putLong(ChunkNbtKeys.GENERATION_EPOCH, epoch);
        return tag;
    }
}
