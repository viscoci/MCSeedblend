package com.bondigi.seedblend.lifecycle;

import com.bondigi.seedblend.SeedBlend;
import com.bondigi.seedblend.config.SeedBlendConfig;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import com.bondigi.seedblend.mixin.WorldGenSettingsAccessor;
import com.bondigi.seedblend.state.PendingTransaction;
import com.bondigi.seedblend.state.SeedBlendStateException;
import com.bondigi.seedblend.state.SeedBlendWorldState;
import com.bondigi.seedblend.state.StateStore;
import com.bondigi.seedblend.state.WorldFingerprint;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;

import org.jetbrains.annotations.Nullable;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The restart-time reseed lifecycle (spec §12). {@link #onServerStarting} runs before
 * any {@code ServerLevel} exists (before {@code RandomState}, structure state, noise
 * routers are built); {@link #onServerStarted} verifies and finalizes; the whole flow is
 * idempotent — a crash before finalization leaves the STAGED transaction to retry.
 *
 * <p>26.1: the canonical seed lives in the {@code WorldGenSettings} SavedData rather
 * than level.dat's WorldOptions.
 */
public final class StartupSeedTransaction {
    /** State loaded during the current startup; null when the mod is passive. */
    @Nullable
    private static SeedBlendWorldState worldState;
    @Nullable
    private static PendingTransaction applying;

    private StartupSeedTransaction() {
    }

    @Nullable
    public static SeedBlendWorldState worldState() {
        return worldState;
    }

    /**
     * The first {@code /seedblend commit} on a previously passive world creates state at
     * runtime; adopt it so status/cancel see it. Chunk hooks stay passive until restart —
     * the runtime state is only ever published during startup (spec §17).
     */
    public static void adoptState(SeedBlendWorldState state) {
        worldState = state;
    }

    public static Path worldDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    public static long levelSeed(MinecraftServer server) {
        return server.getWorldGenSettings().options().seed();
    }

    /**
     * Pre-level hook. Fired by loader events that run before {@code createLevels}
     * (Fabric SERVER_STARTING / NeoForge ServerAboutToStartEvent) on both dedicated and
     * integrated servers.
     */
    public static void onServerStarting(MinecraftServer server) {
        SeedBlendConfig config = SeedBlendConfig.loadOrCreate(configDir(server));
        SeedBlendRuntime.setConfig(config);

        Path worldDir = worldDir(server);
        Optional<SeedBlendWorldState> loaded = StateStore.load(worldDir);
        if (loaded.isEmpty()) {
            // Mod installed but never used: make no world changes at all (spec §21.3).
            SeedBlend.LOGGER.info("[SeedBlend] No state for this world — passive (no world changes)");
            worldState = null;
            applying = null;
            return;
        }

        SeedBlendWorldState state = loaded.get();
        String levelName = server.getWorldData().getLevelName();
        long levelSeed = levelSeed(server);

        // Fail closed: state file copied from an unrelated world (spec §7, §19).
        if (!WorldFingerprint.matches(state, levelName)) {
            throw new SeedBlendStateException(
                    "SeedBlend state file does not belong to this world (fingerprint mismatch for level '"
                            + levelName + "'). Refusing to start rather than silently reseeding.");
        }

        PendingTransaction pending = state.pendingTransaction();
        if (pending != null && pending.isStaged()) {
            if (pending.targetEpoch() != state.activeEpoch() + 1) {
                throw new SeedBlendStateException("Pending transaction " + pending.transactionId()
                        + " targets epoch " + pending.targetEpoch() + " but active epoch is "
                        + state.activeEpoch() + " — state is inconsistent, refusing to start.");
            }
            if (levelSeed != state.activeSeed() && levelSeed != pending.targetSeed()) {
                throw new SeedBlendStateException("World seed " + levelSeed
                        + " matches neither the active seed " + state.activeSeed()
                        + " nor the staged seed " + pending.targetSeed() + " — refusing to start.");
            }
            applySeed(server, pending.targetSeed());
            applying = pending;
            SeedBlend.LOGGER.info("[SeedBlend] Applying staged reseed transaction {} (seed {} -> {}, epoch {} -> {})",
                    pending.transactionId(), state.activeSeed(), pending.targetSeed(),
                    state.activeEpoch(), pending.targetEpoch());
        } else {
            applying = null;
            if (levelSeed != state.activeSeed()) {
                throw new SeedBlendStateException("World seed " + levelSeed
                        + " differs from the SeedBlend active seed " + state.activeSeed()
                        + " — world and state file are out of sync, refusing to start.");
            }
        }

        worldState = state;

        long effectiveEpoch = applying != null ? applying.targetEpoch() : state.activeEpoch();
        long effectiveSeed = applying != null ? applying.targetSeed() : state.activeSeed();
        Set<String> blendingDims = new HashSet<>(config.supportedDimensions);

        java.util.Map<Long, Long> seedHistory = new java.util.HashMap<>();
        state.seedHistory().forEach((epoch, seed) -> seedHistory.put(Long.parseLong(epoch), seed));
        seedHistory.put(effectiveEpoch, effectiveSeed);

        Set<String> transitionDims = config.transition.enabled
                ? new HashSet<>(config.transition.dimensions)
                : Set.of();
        SeedBlendRuntime.publish(new SeedBlendRuntimeState(
                effectiveSeed, effectiveEpoch, state.mode(), blendingDims,
                seedHistory, transitionDims, config.transition.clampedRange(),
                config.transition.blendBiomes));
    }

    /**
     * Replace the canonical world seed before any world-generation object is built.
     * Marking the SavedData dirty persists the new seed through the normal save path.
     */
    private static void applySeed(MinecraftServer server, long newSeed) {
        WorldGenSettings settings = server.getWorldGenSettings();
        WorldOptions old = settings.options();
        WorldOptions replaced = new WorldOptions(newSeed, old.generateStructures(), old.generateBonusChest());
        ((WorldGenSettingsAccessor) (Object) settings).seedblend$setOptions(replaced);
        settings.setDirty();
    }

    /**
     * Post-start hook (Fabric SERVER_STARTED / NeoForge ServerStartedEvent). Verifies
     * every level uses the expected seed, then finalizes the transaction atomically.
     * No silent fallback: mismatch → hard failure (spec §19).
     */
    public static void onServerStarted(MinecraftServer server) {
        SeedBlendWorldState state = worldState;
        if (state == null) {
            return;
        }

        long expectedSeed = applying != null ? applying.targetSeed() : state.activeSeed();
        long actual = levelSeed(server);
        if (actual != expectedSeed) {
            throw new SeedBlendStateException("Post-start verification failed: world seed "
                    + actual + " != expected seed " + expectedSeed);
        }
        for (ServerLevel level : server.getAllLevels()) {
            long structureSeed = level.getChunkSource().getGeneratorState().getLevelSeed();
            if (structureSeed != expectedSeed) {
                throw new SeedBlendStateException("Post-start verification failed: dimension "
                        + level.dimension().identifier() + " structure state uses seed " + structureSeed
                        + " != expected " + expectedSeed
                        + ". The selected seed is not active; refusing to continue (spec §19).");
            }
        }

        if (applying != null) {
            state.finalizeTransaction(applying);
            StateStore.save(worldDir(server), state);
            SeedBlend.LOGGER.info("[SeedBlend] Reseed transaction {} finalized", applying.transactionId());
            applying = null;
        } else if (state.lastSuccessfulStartupEpoch() != state.activeEpoch()) {
            state.markSuccessfulStartup();
            StateStore.save(worldDir(server), state);
        }

        logStartupSummary(server, state);
    }

    private static void logStartupSummary(MinecraftServer server, SeedBlendWorldState state) {
        SeedBlend.LOGGER.info("[SeedBlend] Active epoch: {}", state.activeEpoch());
        SeedBlend.LOGGER.info("[SeedBlend] Active generation seed: {}", state.activeSeed());
        SeedBlend.LOGGER.info("[SeedBlend] Mode: {}", state.mode().id());
        var runtime = SeedBlendRuntime.state();
        if (runtime != null) {
            for (String dim : runtime.blendingDimensions()) {
                SeedBlend.LOGGER.info("[SeedBlend] Native blending enabled for {}", dim);
            }
        }
        if (runtime != null && !runtime.transitionDimensions().isEmpty()) {
            SeedBlend.LOGGER.info("[SeedBlend] Transition blending: {} chunks, biomes {}, dimensions: {}",
                    runtime.transitionRange(), runtime.blendBiomes() ? "blended" : "not blended",
                    String.join(", ", runtime.transitionDimensions()));
        } else {
            SeedBlend.LOGGER.info("[SeedBlend] Transition blending disabled");
        }
        SeedBlend.LOGGER.info("[SeedBlend] Vanilla blending_data injection remains Overworld-only");
        if (SeedBlendRuntime.config().warnOnUnsupportedDimensions) {
            for (ServerLevel level : server.getAllLevels()) {
                String id = level.dimension().identifier().toString();
                if (runtime != null && !runtime.isBlendingDimension(id)
                        && !runtime.isTransitionDimension(id) && level.dimension() != Level.OVERWORLD) {
                    SeedBlend.LOGGER.warn(
                            "[SeedBlend] Dimension {} is not blended; future chunks there still use the new seed",
                            id);
                }
            }
        }
    }

    /** Server fully stopped — clear per-run state (integrated servers restart in-process). */
    public static void onServerStopped(MinecraftServer server) {
        worldState = null;
        applying = null;
        SeedBlendRuntime.reset();
    }

    private static Path configDir(MinecraftServer server) {
        return ConfigDirHolder.get();
    }

    /** Loader entrypoints set the loader-resolved config directory during mod init. */
    public static final class ConfigDirHolder {
        private static volatile Path configDir = Path.of("config");

        private ConfigDirHolder() {
        }

        public static void set(Path dir) {
            configDir = dir;
        }

        public static Path get() {
            return configDir;
        }
    }
}
