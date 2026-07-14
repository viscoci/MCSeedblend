package com.bondigi.seedblend.command;

import com.bondigi.seedblend.SeedBlend;
import com.bondigi.seedblend.chunk.ChunkNbtKeys;
import com.bondigi.seedblend.chunk.ChunkNbtTransformer;
import com.bondigi.seedblend.chunk.DimensionBlendPolicy;
import com.bondigi.seedblend.chunk.DimensionPolicyFactory;
import com.bondigi.seedblend.core.EpochPolicy;
import com.bondigi.seedblend.core.PlanTokenService;
import com.bondigi.seedblend.core.SeedBlendRuntime;
import com.bondigi.seedblend.core.SeedBlendRuntimeState;
import com.bondigi.seedblend.core.SeedParser;
import com.bondigi.seedblend.lifecycle.StartupSeedTransaction;
import com.bondigi.seedblend.state.PendingTransaction;
import com.bondigi.seedblend.state.SeedBlendWorldState;
import com.bondigi.seedblend.state.StateStore;
import com.bondigi.seedblend.state.WorldFingerprint;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The /seedblend command tree (spec §13). All modifying commands require permission
 * level 4. Command implementations are loader-agnostic; each loader module only
 * registers the tree with its own callback.
 */
public final class SeedBlendCommands {
    private static final PlanTokenService PLAN_TOKENS = new PlanTokenService();

    private SeedBlendCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("seedblend")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("status").executes(SeedBlendCommands::status))
                .then(Commands.literal("plan")
                        .then(Commands.argument("seed", StringArgumentType.greedyString())
                                .executes(SeedBlendCommands::plan)))
                .then(Commands.literal("commit")
                        .then(Commands.argument("token", StringArgumentType.word())
                                .executes(SeedBlendCommands::commit)))
                .then(Commands.literal("cancel").executes(SeedBlendCommands::cancel))
                .then(Commands.literal("inspect")
                        .then(Commands.literal("chunk")
                                .then(Commands.argument("chunk-x", IntegerArgumentType.integer())
                                        .then(Commands.argument("chunk-z", IntegerArgumentType.integer())
                                                .executes(ctx -> inspect(ctx, ctx.getSource().getLevel()))
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(ctx -> inspect(ctx,
                                                                DimensionArgument.getDimension(ctx, "dimension"))))))))
                .then(Commands.literal("verify").executes(SeedBlendCommands::verify)));
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendFailure(Component.literal(text));
        return 0;
    }

    // --- /seedblend status ---

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        SeedBlendWorldState state = StartupSeedTransaction.worldState();
        long levelSeed = server.getWorldData().worldGenOptions().seed();
        StringBuilder out = new StringBuilder("SeedBlend status\n");

        if (state == null) {
            out.append("Mode: passive (no reseed has ever been committed on this world)\n");
            out.append("Active epoch: 0\n");
            out.append("Level seed: ").append(levelSeed).append('\n');
        } else {
            out.append("Active epoch: ").append(state.activeEpoch()).append('\n');
            out.append("Active seed: ").append(state.activeSeed()).append('\n');
            out.append("Operating mode: ").append(state.mode().id()).append('\n');
            PendingTransaction pending = state.pendingTransaction();
            if (pending != null) {
                out.append("Pending seed: ").append(pending.targetSeed()).append('\n');
                out.append("Pending transaction: ").append(pending.transactionId()).append('\n');
                out.append("Restart required: yes — the staged reseed applies at next startup\n");
            } else {
                out.append("Pending transaction: none\n");
                out.append("Restart required: no\n");
            }
            if (levelSeed != state.activeSeed()) {
                out.append("WARNING: level.dat seed ").append(levelSeed)
                        .append(" disagrees with SeedBlend state ").append(state.activeSeed()).append('\n');
            }
        }
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        out.append("Blending dimensions: ").append(runtime != null
                ? String.join(", ", runtime.blendingDimensions())
                : String.join(", ", SeedBlendRuntime.config().supportedDimensions));
        reply(ctx, out.toString());
        return 1;
    }

    // --- /seedblend plan <seed> ---

    private static int plan(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String seedInput = StringArgumentType.getString(ctx, "seed");
        OptionalLong parsed = SeedParser.parse(seedInput);
        if (parsed.isEmpty()) {
            return fail(ctx, "Invalid seed: '" + seedInput + "'");
        }
        long newSeed = parsed.getAsLong();

        SeedBlendWorldState state = StartupSeedTransaction.worldState();
        long currentSeed = state != null ? state.activeSeed() : server.getWorldData().worldGenOptions().seed();
        long currentEpoch = state != null ? state.activeEpoch() : 0L;

        if (newSeed == currentSeed) {
            return fail(ctx, "New seed equals the current seed — nothing to do.");
        }
        if (state != null && state.pendingTransaction() != null) {
            return fail(ctx, "A reseed is already staged (transaction "
                    + state.pendingTransaction().transactionId() + "). Cancel it first with /seedblend cancel.");
        }

        PlanTokenService.Plan plan = PLAN_TOKENS.issue(newSeed, currentEpoch, ctx.getSource().getTextName());

        reply(ctx, """
                SeedBlend reseed plan

                Current seed: %d
                New seed: %d
                Current epoch: %d
                New epoch: %d

                Existing Overworld chunks will be preserved.
                Future Overworld chunks will use native terrain blending.

                Warning:
                The canonical world seed will change.
                Future Nether and End chunks will also use the new seed, but SeedBlend
                does not currently blend those dimensions.
                Seed-dependent behavior in existing chunks (slime chunks, future
                structure placement, seed-reading mods and commands) will change.

                A complete world backup is strongly recommended.

                Commit with:
                /seedblend commit %s""".formatted(currentSeed, newSeed, currentEpoch, currentEpoch + 1, plan.token()));
        return 1;
    }

    // --- /seedblend commit <token> ---

    private static int commit(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String token = StringArgumentType.getString(ctx, "token");

        SeedBlendWorldState state = StartupSeedTransaction.worldState();
        long currentEpoch = state != null ? state.activeEpoch() : 0L;

        Optional<PlanTokenService.Plan> validated = PLAN_TOKENS.validate(token, currentEpoch);
        if (validated.isEmpty()) {
            return fail(ctx, "Unknown, stale, or expired plan token. Run /seedblend plan <seed> again.");
        }
        PlanTokenService.Plan plan = validated.get();

        if (state == null) {
            // First ever reseed on this world: adopt it (fingerprint from the original seed).
            long originalSeed = server.getWorldData().worldGenOptions().seed();
            String levelName = server.getWorldData().getLevelName();
            state = new SeedBlendWorldState(WorldFingerprint.compute(originalSeed, levelName), originalSeed);
        } else if (state.pendingTransaction() != null) {
            return fail(ctx, "A reseed is already staged. Cancel it first with /seedblend cancel.");
        }

        String transactionId = UUID.randomUUID().toString().substring(0, 8);
        state.stageTransaction(new PendingTransaction(transactionId, plan.targetSeed(),
                currentEpoch + 1, plan.plannedBy(), PendingTransaction.STATE_STAGED));
        StateStore.save(StartupSeedTransaction.worldDir(server), state);
        StartupSeedTransaction.adoptState(state);
        PLAN_TOKENS.clear();

        SeedBlend.LOGGER.info("[SeedBlend] Reseed transaction {} staged by {} (target seed {}, epoch {})",
                transactionId, plan.plannedBy(), plan.targetSeed(), currentEpoch + 1);
        reply(ctx, "Reseed staged (transaction " + transactionId + ").\n"
                + "RESTART REQUIRED: the new seed takes effect at the next server startup.\n"
                + "Nothing changes until then. Make a complete world backup before restarting.");
        return 1;
    }

    // --- /seedblend cancel ---

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        SeedBlendWorldState state = StartupSeedTransaction.worldState();
        if (state == null || state.pendingTransaction() == null) {
            return fail(ctx, "No staged reseed to cancel.");
        }
        String id = state.pendingTransaction().transactionId();
        state.cancelTransaction();
        StateStore.save(StartupSeedTransaction.worldDir(server), state);
        reply(ctx, "Staged reseed transaction " + id + " cancelled. No changes were made.");
        return 1;
    }

    // --- /seedblend inspect chunk <x> <z> [dimension] ---

    private static int inspect(CommandContext<CommandSourceStack> ctx, ServerLevel level) throws CommandSyntaxException {
        int x = IntegerArgumentType.getInteger(ctx, "chunk-x");
        int z = IntegerArgumentType.getInteger(ctx, "chunk-z");
        ChunkPos pos = new ChunkPos(x, z);

        // Read serialized NBT directly — must not generate or load the chunk (spec §13).
        Optional<CompoundTag> maybeTag;
        try {
            maybeTag = level.getChunkSource().chunkMap.read(pos)
                    .orTimeout(10, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            return fail(ctx, "Failed to read chunk NBT: " + e);
        }

        DimensionBlendPolicy policy = DimensionPolicyFactory.of(level);
        SeedBlendRuntimeState runtime = SeedBlendRuntime.state();
        long activeEpoch = runtime != null ? runtime.activeEpoch() : 0L;

        StringBuilder out = new StringBuilder();
        out.append("Chunk [").append(x).append(", ").append(z).append("] in ")
                .append(level.dimension().location()).append('\n');
        if (maybeTag.isEmpty()) {
            out.append("Not generated (no serialized data). Inspect does not generate chunks.");
            reply(ctx, out.toString());
            return 1;
        }
        CompoundTag tag = maybeTag.get();
        long chunkEpoch = ChunkNbtTransformer.readEpoch(tag);
        boolean complete = ChunkNbtTransformer.isStatusComplete(tag);
        boolean hasBlending = tag.contains(ChunkNbtKeys.BLENDING_DATA);
        EpochPolicy.Classification cls = EpochPolicy.classify(chunkEpoch, activeEpoch);
        boolean wouldInject = cls == EpochPolicy.Classification.OLD && complete
                && policy.blendingSupported() && !hasBlending;

        out.append("Status: ").append(tag.getString(ChunkNbtKeys.STATUS)).append('\n');
        out.append("Serialized generation epoch: ").append(chunkEpoch)
                .append(tag.contains(ChunkNbtKeys.SEEDBLEND) ? "" : " (no metadata — implicit epoch 0)").append('\n');
        out.append("Active epoch: ").append(activeEpoch).append('\n');
        out.append("Considered old: ").append(cls == EpochPolicy.Classification.OLD ? "yes" : "no").append('\n');
        out.append("Blending data present: ").append(hasBlending ? "yes" : "no").append('\n');
        out.append("Synthetic blending would be injected on load: ").append(wouldInject ? "yes" : "no").append('\n');
        out.append("Blending sections: min ").append(policy.minSection())
                .append(", max (exclusive) ").append(policy.maxSectionExclusive());
        reply(ctx, out.toString());
        return 1;
    }

    // --- /seedblend verify ---

    private static int verify(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        SeedBlendWorldState memory = StartupSeedTransaction.worldState();
        StringBuilder out = new StringBuilder("SeedBlend verify\n");
        boolean ok = true;

        // State-file consistency: reload from disk and compare with memory.
        try {
            Optional<SeedBlendWorldState> disk = StateStore.load(StartupSeedTransaction.worldDir(server));
            if (memory == null && disk.isEmpty()) {
                out.append("[ok] no state on disk or in memory (passive)\n");
            } else if (memory != null && disk.isPresent()
                    && disk.get().activeEpoch() == memory.activeEpoch()
                    && disk.get().activeSeed() == memory.activeSeed()) {
                out.append("[ok] state file matches runtime state\n");
            } else {
                ok = false;
                out.append("[FAIL] state file and runtime state disagree\n");
            }
        } catch (Exception e) {
            ok = false;
            out.append("[FAIL] state file unreadable: ").append(e.getMessage()).append('\n');
        }

        // Seed consistency.
        long levelSeed = server.getWorldData().worldGenOptions().seed();
        long expected = memory != null ? memory.activeSeed() : levelSeed;
        if (levelSeed == expected) {
            out.append("[ok] level.dat seed matches active seed (").append(levelSeed).append(")\n");
        } else {
            ok = false;
            out.append("[FAIL] level.dat seed ").append(levelSeed)
                    .append(" != active seed ").append(expected).append('\n');
        }

        // Pending transaction consistency.
        PendingTransaction pending = memory != null ? memory.pendingTransaction() : null;
        if (pending == null) {
            out.append("[ok] no pending transaction\n");
        } else if (pending.isStaged() && pending.targetEpoch() == memory.activeEpoch() + 1) {
            out.append("[ok] staged transaction ").append(pending.transactionId())
                    .append(" is consistent (restart required)\n");
        } else {
            ok = false;
            out.append("[FAIL] pending transaction is inconsistent: ").append(pending).append('\n');
        }

        // Generator support per level.
        for (ServerLevel level : server.getAllLevels()) {
            DimensionBlendPolicy policy = DimensionPolicyFactory.of(level);
            out.append(policy.blendingSupported() ? "[ok] " : "[info] ")
                    .append(level.dimension().location())
                    .append(policy.blendingSupported() ? " supports blending\n" : " not blended\n");
        }

        // Runtime incident counters.
        long futureRejected = SeedBlendRuntime.FUTURE_EPOCH_REJECTED.sum();
        long malformed = SeedBlendRuntime.MALFORMED_METADATA.sum();
        out.append(futureRejected == 0 ? "[ok] " : "[FAIL] ")
                .append(futureRejected).append(" future-epoch chunks rejected this runtime\n");
        out.append(malformed == 0 ? "[ok] " : "[warn] ")
                .append(malformed).append(" malformed SeedBlend metadata encountered this runtime\n");
        out.append("Counters: oldCompleted=").append(SeedBlendRuntime.OLD_COMPLETED_CHUNKS.sum())
                .append(" blendingInjected=").append(SeedBlendRuntime.SYNTHETIC_BLENDING_INJECTED.sum())
                .append(" oldIncompleteDiscarded=").append(SeedBlendRuntime.OLD_INCOMPLETE_DISCARDED.sum())
                .append(" newChunksStamped=").append(SeedBlendRuntime.NEW_CHUNKS_ASSIGNED_EPOCH.sum())
                .append(" missingMetadata=").append(SeedBlendRuntime.CHUNKS_MISSING_METADATA.sum());

        out.append(ok ? "\nResult: OK" : "\nResult: PROBLEMS FOUND");
        reply(ctx, out.toString());
        return ok ? 1 : 0;
    }
}
