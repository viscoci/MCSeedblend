package com.bondigi.seedblend.mixin;

import com.bondigi.seedblend.chunk.ChunkNbtTransformer;
import com.bondigi.seedblend.chunk.ChunkReadResult;
import com.bondigi.seedblend.chunk.DimensionBlendPolicy;
import com.bondigi.seedblend.chunk.DimensionPolicyFactory;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Intercepts serialized chunk NBT after it is read from disk (and datafixed), before
 * vanilla decodes it (spec §9). Old incomplete chunks map to {@code Optional.empty()},
 * which vanilla treats as an absent chunk and regenerates with the active seed (spec §10).
 *
 * <p>Deliberately avoids chunk scheduling internals for C2ME-style compatibility
 * (spec §16): only the read future's result is transformed, on the same async stage.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "readChunk", at = @At("RETURN"), cancellable = true)
    private void seedblend$transformChunkTag(ChunkPos pos,
                                             CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        if (runtime == null) {
            return; // Passive: no state file, make no changes at all.
        }
        DimensionBlendPolicy policy = DimensionPolicyFactory.of(this.level);
        long activeEpoch = runtime.activeEpoch();
        cir.setReturnValue(cir.getReturnValue().thenApply(maybeTag -> {
            if (maybeTag.isEmpty()) {
                return maybeTag;
            }
            CompoundTag tag = maybeTag.get();
            ChunkReadResult result = ChunkNbtTransformer.process(tag, policy, activeEpoch);
            return result == ChunkReadResult.DISCARD_AND_REGENERATE ? Optional.empty() : maybeTag;
        }));
    }
}
