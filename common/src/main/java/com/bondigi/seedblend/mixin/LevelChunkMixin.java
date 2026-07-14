package com.bondigi.seedblend.mixin;

import com.bondigi.seedblend.chunk.SeedBlendChunkEpochAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries the generation epoch across the ProtoChunk → LevelChunk promotion; the two are
 * distinct objects, so the duck field does not transfer by itself.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("TAIL"))
    private void seedblend$copyEpochFromProto(ServerLevel level, ProtoChunk protoChunk,
                                              LevelChunk.PostLoadProcessor postLoad, CallbackInfo ci) {
        SeedBlendChunkEpochAccess proto = (SeedBlendChunkEpochAccess) protoChunk;
        if (proto.seedblend$hasAssignedGenerationEpoch()) {
            ((SeedBlendChunkEpochAccess) this).seedblend$setGenerationEpoch(proto.seedblend$getGenerationEpoch());
        }
    }
}
