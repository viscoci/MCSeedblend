package com.bondigi.seedblend.mixin;

import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Replaces the world options (seed carrier) before any world-generation object is
 * constructed (spec §12). In 26.1 the seed lives in the {@code WorldGenSettings}
 * SavedData; marking it dirty afterwards persists the new canonical seed.
 */
@Mixin(WorldGenSettings.class)
public interface WorldGenSettingsAccessor {
    @Mutable
    @Accessor("options")
    void seedblend$setOptions(WorldOptions options);
}
