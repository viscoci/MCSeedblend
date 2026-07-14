package com.bondigi.seedblend.mixin;

import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Replaces the world options (seed carrier) on the loaded level data, before any
 * world-generation object is constructed (spec §12). The normal save path then
 * persists the new canonical seed to level.dat.
 */
@Mixin(PrimaryLevelData.class)
public interface PrimaryLevelDataAccessor {
    @Mutable
    @Accessor("worldOptions")
    void seedblend$setWorldOptions(WorldOptions options);
}
