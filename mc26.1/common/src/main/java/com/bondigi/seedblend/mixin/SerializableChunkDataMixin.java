package com.bondigi.seedblend.mixin;

import com.bondigi.seedblend.chunk.ChunkNbtKeys;
import com.bondigi.seedblend.chunk.ChunkNbtTransformer;
import com.bondigi.seedblend.chunk.DimensionBlendPolicy;
import com.bondigi.seedblend.chunk.DimensionPolicyFactory;
import com.bondigi.seedblend.chunk.SeedBlendChunkDataAccess;
import com.bondigi.seedblend.chunk.SeedBlendChunkEpochAccess;
import com.bondigi.seedblend.core.EpochPolicy;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Owner of {@code blending_data} in 26.1. {@code SerializableChunkData} rebuilds chunk
 * NBT from its record fields, so SeedBlend metadata is captured at parse/copyOf and
 * re-appended at write (spec §11). All injections are additive.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin implements SeedBlendChunkDataAccess {
    @Unique
    private long seedblend$epoch = -1L;
    @Unique
    @Nullable
    private DimensionBlendPolicy seedblend$policy;
    @Unique
    private int seedblend$transitionWeight;

    @Override
    public int seedblend$getTransitionWeight() {
        return seedblend$transitionWeight;
    }

    @Override
    public void seedblend$setTransitionWeight(int weightPercent) {
        this.seedblend$transitionWeight = weightPercent;
    }

    @Override
    public long seedblend$getEpoch() {
        return seedblend$epoch;
    }

    @Override
    public void seedblend$setEpoch(long epoch) {
        this.seedblend$epoch = epoch;
    }

    @Override
    public boolean seedblend$hasEpoch() {
        return seedblend$epoch >= 0;
    }

    @Override
    public void seedblend$setPolicy(DimensionBlendPolicy policy) {
        this.seedblend$policy = policy;
    }

    @Override
    @Nullable
    public DimensionBlendPolicy seedblend$getPolicy() {
        return seedblend$policy;
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void seedblend$captureEpochOnParse(LevelHeightAccessor levelHeight,
                                                      PalettedContainerFactory containerFactory,
                                                      CompoundTag chunkData,
                                                      CallbackInfoReturnable<SerializableChunkData> cir) {
        SerializableChunkData result = cir.getReturnValue();
        if (result == null || SeedBlendRuntime.state() == null) {
            return;
        }
        SeedBlendChunkDataAccess access = (SeedBlendChunkDataAccess) (Object) result;
        access.seedblend$setEpoch(ChunkNbtTransformer.readEpoch(chunkData));
        access.seedblend$setTransitionWeight(chunkData.getCompoundOrEmpty(ChunkNbtKeys.SEEDBLEND)
                .getIntOr(ChunkNbtKeys.TRANSITION_WEIGHT, 0));
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void seedblend$stampEpochOnRead(ServerLevel level, PoiManager poiManager,
                                            RegionStorageInfo regionInfo, ChunkPos pos,
                                            CallbackInfoReturnable<ProtoChunk> cir) {
        if (SeedBlendRuntime.state() == null || !seedblend$hasEpoch()) {
            return;
        }
        ProtoChunk result = cir.getReturnValue();
        SeedBlendChunkEpochAccess resultAccess = (SeedBlendChunkEpochAccess) result;
        resultAccess.seedblend$setGenerationEpoch(seedblend$epoch);
        resultAccess.seedblend$setTransitionWeight(seedblend$transitionWeight);
        if (result instanceof ImposterProtoChunk imposter) {
            // Full chunks round-trip as a wrapped LevelChunk; the wrapper is discarded.
            SeedBlendChunkEpochAccess wrapped = (SeedBlendChunkEpochAccess) imposter.getWrapped();
            wrapped.seedblend$setGenerationEpoch(seedblend$epoch);
            wrapped.seedblend$setTransitionWeight(seedblend$transitionWeight);
        }
    }

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void seedblend$captureEpochOnCopy(ServerLevel level, ChunkAccess chunk,
                                                     CallbackInfoReturnable<SerializableChunkData> cir) {
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        if (runtime == null) {
            return;
        }
        SeedBlendChunkEpochAccess epochAccess = (SeedBlendChunkEpochAccess) chunk;
        long epoch;
        if (epochAccess.seedblend$hasAssignedGenerationEpoch()) {
            epoch = epochAccess.seedblend$getGenerationEpoch();
        } else {
            // New in-memory chunk: assign the current active epoch (spec §11).
            epoch = runtime.activeEpoch();
            epochAccess.seedblend$setGenerationEpoch(epoch);
            SeedBlendRuntime.NEW_CHUNKS_ASSIGNED_EPOCH.increment();
        }
        SeedBlendChunkDataAccess access = (SeedBlendChunkDataAccess) (Object) cir.getReturnValue();
        access.seedblend$setEpoch(epoch);
        access.seedblend$setPolicy(DimensionPolicyFactory.of(level));
        access.seedblend$setTransitionWeight(epochAccess.seedblend$getTransitionWeight());
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void seedblend$persistEpochOnWrite(CallbackInfoReturnable<CompoundTag> cir) {
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        if (runtime == null || !seedblend$hasEpoch()) {
            return;
        }
        CompoundTag tag = cir.getReturnValue();
        ChunkNbtTransformer.ensureSeedBlendMetadata(tag, seedblend$epoch);
        if (seedblend$transitionWeight > 0) {
            tag.getCompound(ChunkNbtKeys.SEEDBLEND)
                    .ifPresent(meta -> meta.putInt(ChunkNbtKeys.TRANSITION_WEIGHT, seedblend$transitionWeight));
        }

        // Keep old completed chunks valid blending sources across save cycles.
        DimensionBlendPolicy policy = seedblend$policy;
        if (policy != null
                && EpochPolicy.classify(seedblend$epoch, runtime.activeEpoch()) == EpochPolicy.Classification.OLD
                && ChunkNbtTransformer.isStatusComplete(tag)
                && SeedBlendRuntime.config().persistSyntheticBlendingData
                && policy.blendingSupported()
                && !tag.contains(ChunkNbtKeys.BLENDING_DATA)) {
            ChunkNbtTransformer.ensureBlendingData(tag, policy);
        }
    }
}
