package com.bondigi.seedblend.chunk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Creates and validates the vanilla {@code blending_data} compound. Only the vertical
 * bounds are synthesized; height arrays are left for vanilla to calculate lazily, which
 * matches what vanilla writes for pre-1.18 upgrade chunks.
 */
public final class BlendingDataFactory {
    private BlendingDataFactory() {
    }

    public static CompoundTag createTag(int minSection, int maxSectionExclusive) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(ChunkNbtKeys.MIN_SECTION, minSection);
        tag.putInt(ChunkNbtKeys.MAX_SECTION, maxSectionExclusive);
        return tag;
    }

    /**
     * A decodable compound needs numeric {@code min_section} and {@code max_section}
     * with min < max. Anything else fails {@code BlendingData.CODEC} in 1.21.1.
     */
    public static boolean isValid(Tag tag) {
        if (!(tag instanceof CompoundTag compound)) {
            return false;
        }
        if (!compound.contains(ChunkNbtKeys.MIN_SECTION, Tag.TAG_ANY_NUMERIC)
                || !compound.contains(ChunkNbtKeys.MAX_SECTION, Tag.TAG_ANY_NUMERIC)) {
            return false;
        }
        return compound.getInt(ChunkNbtKeys.MIN_SECTION) < compound.getInt(ChunkNbtKeys.MAX_SECTION);
    }
}
