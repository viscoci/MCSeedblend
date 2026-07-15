package com.bondigi.seedblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Constructor access: transition blending needs a real (non-EMPTY) Blender instance in
 * dimensions that have no vanilla blending data, because EMPTY's overridden methods
 * short-circuit past the injected transition hooks.
 */
@Mixin(Blender.class)
public interface BlenderInvoker {
    @Invoker("<init>")
    static Blender seedblend$create(Long2ObjectOpenHashMap<BlendingData> heightAndBiomeData,
                                    Long2ObjectOpenHashMap<BlendingData> densityData) {
        throw new AssertionError("mixin invoker not applied");
    }
}
