package com.bondigi.seedblend.neoforge;

import com.bondigi.seedblend.SeedBlend;
import com.bondigi.seedblend.lifecycle.StartupSeedTransaction;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(SeedBlend.MOD_ID)
public final class SeedBlendNeoForge {
    public SeedBlendNeoForge() {
        SeedBlend.init();
        StartupSeedTransaction.ConfigDirHolder.set(FMLPaths.CONFIGDIR.get());

        // ServerAboutToStartEvent fires before levels load — early enough to replace the
        // seed ahead of RandomState/structure-state construction (spec §12).
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) ->
                StartupSeedTransaction.onServerStarting(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                StartupSeedTransaction.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) ->
                StartupSeedTransaction.onServerStopped(event.getServer()));
    }
}
