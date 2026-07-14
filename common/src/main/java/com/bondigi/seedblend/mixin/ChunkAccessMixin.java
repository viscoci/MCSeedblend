package com.bondigi.seedblend.mixin;

import com.bondigi.seedblend.chunk.SeedBlendChunkEpochAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Attaches the in-memory generation epoch to every chunk (spec §11). {@code -1} means
 * "not assigned yet"; deserialization stamps the persisted epoch, serialization assigns
 * the active epoch to new chunks.
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements SeedBlendChunkEpochAccess {
    @Unique
    private volatile long seedblend$generationEpoch = -1L;

    @Override
    public long seedblend$getGenerationEpoch() {
        return seedblend$generationEpoch;
    }

    @Override
    public void seedblend$setGenerationEpoch(long epoch) {
        this.seedblend$generationEpoch = epoch;
    }

    @Override
    public boolean seedblend$hasAssignedGenerationEpoch() {
        return seedblend$generationEpoch >= 0;
    }
}
