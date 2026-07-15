package com.bondigi.seedblend.mixin;

import com.bondigi.seedblend.chunk.ChunkNbtKeys;
import com.bondigi.seedblend.chunk.ChunkNbtTransformer;
import com.bondigi.seedblend.chunk.DimensionBlendPolicy;
import com.bondigi.seedblend.chunk.DimensionPolicyFactory;
import com.bondigi.seedblend.chunk.SeedBlendChunkEpochAccess;
import com.bondigi.seedblend.core.EpochPolicy;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Owner of {@code blending_data} in 1.21.1. Read side stamps the persisted epoch onto
 * the in-memory chunk; write side persists the epoch (assigning the active epoch to new
 * chunks) and keeps blending metadata on old completed chunks (spec §11).
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {

    @Inject(method = "read", at = @At("RETURN"))
    private static void seedblend$stampEpochOnRead(ServerLevel level, PoiManager poiManager,
                                                   RegionStorageInfo regionStorageInfo, ChunkPos pos,
                                                   CompoundTag tag,
                                                   CallbackInfoReturnable<ProtoChunk> cir) {
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        if (runtime == null) {
            return;
        }
        long epoch = ChunkNbtTransformer.readEpoch(tag);
        int transitionWeight = tag.getCompound(ChunkNbtKeys.SEEDBLEND).getInt(ChunkNbtKeys.TRANSITION_WEIGHT);
        ProtoChunk result = cir.getReturnValue();
        ((SeedBlendChunkEpochAccess) result).seedblend$setGenerationEpoch(epoch);
        ((SeedBlendChunkEpochAccess) result).seedblend$setTransitionWeight(transitionWeight);
        if (result instanceof ImposterProtoChunk imposter) {
            // Full chunks round-trip as a wrapped LevelChunk; the wrapper is discarded.
            SeedBlendChunkEpochAccess wrapped = (SeedBlendChunkEpochAccess) imposter.getWrapped();
            wrapped.seedblend$setGenerationEpoch(epoch);
            wrapped.seedblend$setTransitionWeight(transitionWeight);
        }
    }

    @Inject(method = "write", at = @At("RETURN"))
    private static void seedblend$persistEpochOnWrite(ServerLevel level, ChunkAccess chunk,
                                                      CallbackInfoReturnable<CompoundTag> cir) {
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        if (runtime == null) {
            return; // Passive: never touch serialized chunks.
        }
        CompoundTag tag = cir.getReturnValue();
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
        ChunkNbtTransformer.ensureSeedBlendMetadata(tag, epoch);
        int transitionWeight = epochAccess.seedblend$getTransitionWeight();
        if (transitionWeight > 0) {
            tag.getCompound(ChunkNbtKeys.SEEDBLEND).putInt(ChunkNbtKeys.TRANSITION_WEIGHT, transitionWeight);
        }

        // Keep old completed chunks valid blending sources across save cycles.
        if (EpochPolicy.classify(epoch, runtime.activeEpoch()) == EpochPolicy.Classification.OLD
                && chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL)
                && SeedBlendRuntime.config().persistSyntheticBlendingData) {
            DimensionBlendPolicy policy = DimensionPolicyFactory.of(level);
            if (policy.blendingSupported() && !tag.contains(ChunkNbtKeys.BLENDING_DATA)) {
                ChunkNbtTransformer.ensureBlendingData(tag, policy);
            }
        }
    }
}
