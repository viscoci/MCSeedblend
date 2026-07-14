package com.bondigi.seedblend.fabric;

import com.bondigi.seedblend.SeedBlend;
import com.bondigi.seedblend.lifecycle.StartupSeedTransaction;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class SeedBlendFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        SeedBlend.init();
        StartupSeedTransaction.ConfigDirHolder.set(FabricLoader.getInstance().getConfigDir());

        // SERVER_STARTING fires before levels load — early enough to replace the seed
        // ahead of RandomState/structure-state construction (spec §12).
        ServerLifecycleEvents.SERVER_STARTING.register(StartupSeedTransaction::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(StartupSeedTransaction::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(StartupSeedTransaction::onServerStopped);
    }
}
