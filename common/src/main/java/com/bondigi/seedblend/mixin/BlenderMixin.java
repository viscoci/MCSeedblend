package com.bondigi.seedblend.mixin;

import com.bondigi.seedblend.transition.SeedBlendBlenderAccess;
import com.bondigi.seedblend.transition.SeedBlendTransition;
import com.bondigi.seedblend.transition.TransitionContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SeedBlend transition blending (dual-generator, normalized over a configurable chunk
 * range). Rides on the vanilla Blender because every vanilla noise router — Overworld,
 * Nether, caves, floating islands, End — wraps its final density in a blend_density
 * node, giving one interception point that works in all noise dimensions with no
 * chunk metadata required.
 *
 * <p>EMPTY overrides these methods in an anonymous subclass, so the injections below
 * never run for it; only instances produced by {@code of} (vanilla or the transition
 * replacement) participate.
 */
@Mixin(Blender.class)
public abstract class BlenderMixin implements SeedBlendBlenderAccess {
    @Unique
    @Nullable
    private TransitionContext seedblend$transition;

    @Override
    @Nullable
    public TransitionContext seedblend$getTransition() {
        return seedblend$transition;
    }

    @Override
    public void seedblend$setTransition(TransitionContext context) {
        this.seedblend$transition = context;
    }

    /**
     * Attach a transition context to the chunk's blender. When vanilla found no
     * blending data (EMPTY) but the chunk is inside a transition zone, a fresh real
     * Blender is substituted so the instance hooks apply.
     */
    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void seedblend$attachTransition(@Nullable WorldGenRegion region,
                                                   CallbackInfoReturnable<Blender> cir) {
        if (region == null) {
            return;
        }
        TransitionContext context = SeedBlendTransition.buildContext(region);
        if (context == null) {
            return;
        }
        Blender result = cir.getReturnValue();
        // EMPTY (and its anonymous subclass) bypasses the instance hooks — substitute a
        // real Blender so the transition context takes effect.
        if (result == Blender.empty() || !(result instanceof SeedBlendBlenderAccess)) {
            result = BlenderInvoker.seedblend$create(new Long2ObjectOpenHashMap<>(), new Long2ObjectOpenHashMap<>());
            cir.setReturnValue(result);
        }
        ((SeedBlendBlenderAccess) result).seedblend$setTransition(context);
    }

    /**
     * Terrain: after vanilla height-matching (a no-op without blending data), lerp
     * between the old seed's final density and the new one by the normalized weight.
     * Weight 1 at the boundary reproduces old-seed terrain exactly — the seam is
     * continuous by construction — fading to pure new-seed terrain across the range.
     */
    @Inject(method = "blendDensity", at = @At("RETURN"), cancellable = true)
    private void seedblend$blendDensity(DensityFunction.FunctionContext ctx, double density,
                                        CallbackInfoReturnable<Double> cir) {
        TransitionContext context = seedblend$transition;
        if (context != null) {
            cir.setReturnValue(context.blendDensity(ctx, cir.getReturnValueD()));
        }
    }

    /** Biomes: dithered old-seed/new-seed selection across the transition. */
    @Inject(method = "getBiomeResolver", at = @At("RETURN"), cancellable = true)
    private void seedblend$blendBiomes(BiomeResolver original, CallbackInfoReturnable<BiomeResolver> cir) {
        TransitionContext context = seedblend$transition;
        if (context == null || !context.blendBiomes()) {
            return;
        }
        BiomeResolver vanilla = cir.getReturnValue();
        cir.setReturnValue((x, y, z, sampler) -> {
            if (context.useOldBiome(x, z)) {
                Holder<Biome> old = context.oldBiome(x, y, z);
                if (old != null) {
                    return old;
                }
            }
            return vanilla.getNoiseBiome(x, y, z, sampler);
        });
    }
}
