# SeedBlend MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Server-side mod letting an existing world generate new chunks from a new seed while preserving old chunks, using Minecraft's native `Blender`/`BlendingData` at old/new boundaries.

**Architecture:** MultiLoader-Template layout — `common/` compiled against Mojang-mapped vanilla (VanillaGradle), thin `fabric/` (Loom) and `neoforge/` (ModDevGradle) modules. All logic lives in common: pure functions over chunk `CompoundTag`s, an immutable runtime state record published at startup, a JSON state file with atomic writes, and shared mixins for seed override + chunk NBT interception. A second standalone Gradle build under `mc26.1/` ports the same design to Minecraft 26.1 (Java 25, unobfuscated, Gradle 9.1+).

**Tech Stack:** Java 21 (1.21.1) / Java 25 (26.1), Gradle 8.10 + 9.1, Fabric Loom + Fabric API, NeoForge ModDevGradle, Mixin, Gson (bundled), JUnit 5, SLF4J.

## Global Constraints (verbatim from OG_SPEC.md)

- MC 1.21.1 first; then port to 26.1.x. One artifact per MC version+loader — no universal JAR.
- Artifact names: `seedblend-fabric-1.21.1-0.1.0.jar`, `seedblend-neoforge-1.21.1-0.1.0.jar`.
- No Architectury/Cloth Config/runtime abstraction deps. Mixin permitted.
- MVP uses vanilla `Blender`/`BlendingData` only — no custom interpolation.
- `blending_data: { min_section, max_section }` from dimension's actual min section / exclusive max section — never hard-coded.
- Restart-required reseed; staged transaction applied at next startup before worldgen state init.
- Chunk epochs immutable; missing metadata = epoch 0; epoch > active = refuse world load.
- No full-world scan; classify on natural NBT load.
- Blending default-enabled only for `minecraft:overworld`. Never synthesize blending_data in Nether/End/flat/debug/custom-incompatible dimensions.
- State at `<world>/seedblend/state.json`, atomic write (`.tmp` → `.bak` → move), world fingerprint binding, fail closed on mismatch.
- Chunk metadata: `seedblend: { schema: 1, generation_epoch: <long> }`.
- Never inject blending_data into incomplete chunks; old incomplete chunks discarded + regenerated.
- Preserve pre-existing valid `blending_data`; only synthesize when absent; replace malformed with warning.
- Commands permission level 4: status / plan <seed> / commit <token> / cancel / inspect chunk / verify.
- Config `config/seedblend.json`, plain JSON (bundled Gson), no config-library dep.
- Thread safety: immutable `SeedBlendRuntimeState` record published before level load; `LongAdder` counters; no sync state writes from gen threads; no neighbor loads; no thread-local seed swap.
- SLF4J logging, required startup lines, runtime counters, no telemetry.
- Fail closed: fingerprint mismatch, future-epoch chunk, malformed state, seed not applied → refuse startup. No silent fallback.
- MIT license, README with backup warning, docs/ set per §25.
- Non-goals §22: no live reseed, no Bedrock, no terrain edit, no Nether/End blending, no auto full backup, no GUI.

---

## File Structure (1.21.1 build, repo root)

```
minecraft-seedblend/
├── settings.gradle                  # includes common, fabric, neoforge
├── build.gradle                     # shared config, version = 0.1.0
├── gradle.properties                # mc=1.21.1, group=com.bondigi.seedblend
├── gradle/wrapper/…                 # Gradle 8.10
├── common/
│   ├── build.gradle                 # VanillaGradle + mixin annotation processor off (compile-only)
│   └── src/main/java/com/bondigi/seedblend/
│   │   ├── SeedBlend.java                    # constants, logger, common init
│   │   ├── state/
│   │   │   ├── SeedBlendMode.java            # enum CANONICAL_WORLD_SEED
│   │   │   ├── SeedBlendWorldState.java      # mutable persisted model (schemaVersion, fingerprint, mode, activeEpoch, activeSeed, previousSeed, pendingTransaction, lastSuccessfulStartupEpoch)
│   │   │   ├── PendingTransaction.java       # transactionId, targetSeed, targetEpoch, createdBy, state
│   │   │   ├── StateStore.java               # load/save with atomic pattern + .bak recovery (pure java.nio, Gson)
│   │   │   └── WorldFingerprint.java         # sha256 over original seed + level name
│   │   ├── core/
│   │   │   ├── SeedBlendRuntimeState.java    # record per §17
│   │   │   ├── SeedBlendRuntime.java         # static holder, publish-once; counters (LongAdder)
│   │   │   ├── SeedParser.java               # long-or-text seed → long (vanilla String.hashCode rule)
│   │   │   ├── EpochPolicy.java              # epoch comparison outcomes
│   │   │   └── PlanTokenService.java         # plan token issue/validate (in-memory, expiring)
│   │   ├── chunk/
│   │   │   ├── ChunkNbtKeys.java             # "seedblend", "schema", "generation_epoch", "blending_data", "Status", …
│   │   │   ├── ChunkNbtTransformer.java      # processChunkNbt(...) per §9 — pure CompoundTag in/out
│   │   │   ├── ChunkReadResult.java          # LOAD / DISCARD_AND_REGENERATE
│   │   │   ├── BlendingDataFactory.java      # synthesize {min_section,max_section} from dimension bounds
│   │   │   └── SeedBlendChunkEpochAccess.java# duck interface per §11
│   │   ├── config/SeedBlendConfig.java       # §14 JSON config, Gson, defaults
│   │   ├── command/SeedBlendCommands.java    # Brigadier tree, loader-agnostic via CommandSourceStack
│   │   ├── lifecycle/StartupSeedTransaction.java # §12 startup algorithm, verification, finalize
│   │   └── mixin/
│   │       ├── MinecraftServerMixin.java     # seed override before createLevels + startup verify
│   │       ├── ChunkSerializerMixin.java     # read: transform NBT, attach epoch; write: persist epoch + blending_data
│   │       ├── ChunkMapMixin.java            # discard old incomplete chunk tags (regenerate)
│   │       ├── ChunkAccessMixin.java         # implements SeedBlendChunkEpochAccess (field + methods)
│   │       └── PrimaryLevelDataAccessor.java # @Mutable worldOptions accessor (audit-gated)
│   └── src/main/resources/seedblend.mixins.json
│   └── src/test/java/…                       # JUnit 5 unit tests per §20
├── fabric/
│   ├── build.gradle                          # Loom, mojmap, Fabric API
│   └── src/main/java/com/bondigi/seedblend/fabric/SeedBlendFabric.java
│   └── src/main/resources/fabric.mod.json
├── neoforge/
│   ├── build.gradle                          # ModDevGradle
│   └── src/main/java/com/bondigi/seedblend/neoforge/SeedBlendNeoForge.java
│   └── src/main/resources/META-INF/neoforge.mods.toml
├── mc26.1/                                   # standalone Gradle build, same module trio, ported sources
├── docs/ (architecture, source-audit-1.21.1, source-audit-26.1, world-safety, compatibility, chunk-metadata, reseed-lifecycle)
├── README.md · LICENSE · CONTRIBUTING.md · CHANGELOG.md
```

Key hook targets (1.21.1, Mojang mappings — Task 2 audit verifies exact signatures before mixin code is finalized):

1. **Seed override:** `MinecraftServer#createLevels` HEAD (or earlier `loadLevel`) — runs on both dedicated and integrated servers before `ServerLevel`/`ChunkMap` construction, which is where `RandomState.create` and `ChunkGeneratorStructureState` consume `worldData.worldGenOptions().seed()`. Swap seed via `@Mutable` accessor on `PrimaryLevelData.worldOptions` (audit-gated).
2. **Chunk read:** `ChunkSerializer.read(ServerLevel, PoiManager, ChunkPos, CompoundTag)` — HEAD `@ModifyVariable`/inject to transform the tag (inject blending_data, read epoch); RETURN to stamp epoch on the `ProtoChunk` duck.
3. **Chunk discard:** `ChunkMap#readChunk` (returns `CompletableFuture<Optional<CompoundTag>>`) — map old+incomplete tags to `Optional.empty()` so vanilla regenerates.
4. **Chunk write:** `ChunkSerializer.write(ServerLevel, ChunkAccess)` RETURN — add `seedblend` compound; ensure blending_data persists for old completed chunks in supported dimensions.
5. **`blending_data` owner in 1.21.1:** `ChunkSerializer` (moved to `SerializableChunkData` in 1.21.2+ — matters for 26.1 port).

---

### Task 1: Repo bootstrap
- [x] git init, `.gitignore` (gradle/idea/eclipse/run dirs), MIT `LICENSE`, README stub, commit.
- [x] Gradle 8.10 wrapper bootstrapped (download distribution once to scratchpad, run `gradle wrapper`).

### Task 2: Milestone 0 — source audit (1.21.1)
**Files:** Create `docs/source-audit-1.21.1.md`.
- [x] Scaffold minimal Loom project (fabric module) sufficient to run `genSources`; get decompiled 1.21.1 Mojang-mapped source.
- [x] Answer all 15 questions from spec §26 with file/line evidence: seed init path, earliest safe hook, chunk NBT parse/serialize paths, exact FULL-status threshold, blending_data owner + round-trip behavior, loader event timing vs mixin need, C2ME-safe injection points, removal behavior.
- [x] Record all required mixin targets (class, method, descriptor, injection point) in the audit doc.
- [x] Commit.

### Task 3: Multi-loader skeleton (Milestone 1)
**Files:** root `build.gradle`, `settings.gradle`, `gradle.properties`, `common/build.gradle` (VanillaGradle), `fabric/build.gradle` (Loom + mojmap + Fabric API), `neoforge/build.gradle` (ModDevGradle), entrypoints, `fabric.mod.json`, `neoforge.mods.toml`, empty `seedblend.mixins.json`.
**Produces:** `./gradlew build` → `seedblend-fabric-1.21.1-0.1.0.jar` + `seedblend-neoforge-1.21.1-0.1.0.jar`; both log a startup line.
- [x] Configure modules, verify clean build of both artifacts, commit.

### Task 4: State model + atomic store + fingerprint (Milestone 2a) — TDD
**Interfaces produced:**
- `StateStore.load(Path worldDir) → SeedBlendWorldState` (recovers from `.bak`, throws `SeedBlendStateCorruptException` if both unreadable)
- `StateStore.save(Path worldDir, SeedBlendWorldState)` — atomic §7 procedure
- `WorldFingerprint.compute(long originalSeed, String levelName) → String "sha256-…"`
- `SeedParser.parse(String) → long` (numeric else vanilla `String.hashCode()`; empty invalid)
- `EpochPolicy.classify(long chunkEpoch, long activeEpoch) → CURRENT | OLD | FUTURE_INVALID`
- [x] Write failing JUnit tests: serialization round-trip, tmp/bak recovery (corrupt active file → bak wins), fingerprint mismatch detect, seed parsing (numeric, negative, textual, boundary), epoch classify, transaction idempotence (STAGED survives reload).
- [x] Implement; tests green; commit.

### Task 5: Chunk NBT transformation (Milestone 3+4 core) — TDD
**Interfaces produced:**
- `ChunkNbtTransformer.process(CompoundTag chunkTag, DimensionBlendPolicy dim, SeedBlendRuntimeState st) → ChunkReadResult` implementing spec §9 algorithm verbatim (future-epoch throw, old+incomplete → DISCARD_AND_REGENERATE, old+complete+supported → ensure blending_data, always ensure seedblend compound).
- `BlendingDataFactory.createTag(int minSection, int maxSectionExclusive) → CompoundTag`
- Preserve existing valid blending_data; replace only undecodable (warn).
- [x] Failing tests: missing metadata→epoch 0; epoch stamping; future epoch throws; incomplete old chunk discarded (every pre-FULL status string from audit); complete old chunk gets synthetic blending_data with dimension-true bounds; existing blending_data untouched; malformed replaced+warned; unsupported dimension never gets synthetic data; current-epoch chunk untouched.
- [x] Implement; green; commit.

### Task 6: Mixins + runtime wiring (Milestones 2b/3/4)
**Files:** the five mixins above, `SeedBlendRuntime`, `StartupSeedTransaction`, loader lifecycle hooks (Fabric `ServerLifecycleEvents`, NeoForge `ServerAboutToStartEvent` — only where timing allows; seed override stays a common mixin).
- [x] Startup sequence per §12: load state → verify fingerprint → apply pending seed before `createLevels` → post-init verify actual `ServerLevel` seed == expected → finalize transaction atomically → required log lines. Fail-closed paths per §19.
- [x] ChunkAccess duck + epoch stamp on read/generate/save; counters.
- [x] Build both loaders; commit.

### Task 7: Commands (Milestone 5)
- [x] Brigadier tree in common (`status`, `plan <seed>`, `commit <token>`, `cancel`, `inspect chunk <x> <z> [dim]`, `verify`), permission 4, plan-token flow with §13 exact warning copy, inspect must not generate absent chunks (read region NBT via `ChunkMap` read path w/o ticket).
- [x] Register on both loaders; manual smoke via dedicated server run; commit.

### Task 8: Integration verification (Milestone 6a)
- [x] Headless fixture per §20: scripted dedicated-server runs (seed A gen → hash chunks → plan+commit B → restart → gen boundary → restart → assert epochs/blending/hashes preserved). Implement as a PowerShell/Gradle-driven fixture reading region files with a small NBT-reading test utility.
- [x] Multi-reseed (A→B→C) case; failure-recovery cases (kill after stage, kill before finalize → same transaction retried, no extra epoch).
- [x] GameTest module wiring for both loaders (structure-less tests asserting runtime state + chunk metadata).
- [x] Commit.

### Task 9: Docs + release (Milestone 6b)
- [x] README (backup warning prominent, versions, install, commands, epochs, limitations §23, unsupported dims, removal behavior), CONTRIBUTING, CHANGELOG, docs/architecture.md, world-safety.md, compatibility.md, chunk-metadata.md, reseed-lifecycle.md.
- [x] Verify acceptance criteria §21 checklist; tag 0.1.0 artifacts; commit.

### Task 10: 26.1 port
- [x] Research: Fabric + NeoForge 26.1 porting guides; locate renamed serializer (`SerializableChunkData`), seed path changes, Java 25/Gradle 9.1 toolchains.
- [x] `docs/source-audit-26.1.md` with same 15 answers.
- [x] `mc26.1/` standalone build mirroring module trio; port common sources (shared design, version-specific hooks); both artifacts build; unit tests pass; commit.

## Self-Review notes
- Spec coverage: §1–§26 all mapped (§4→global constraints+T5/T6; §5 warning copy→T7; §7→T4; §8/§9/§10/§11→T5/T6; §12→T6; §13→T7; §14→T4/T6; §15→T3/T6; §16/§17/§18/§19→T6 constraints; §20→T4/T5/T8; §21→T9; §24 milestones→tasks; §25→T9; §26→T2).
- Mixin signatures deliberately audit-gated (Task 2) — spec §24/§26 mandates source audit before finalizing targets; plan encodes candidates + verification step instead of guessed code.
- Forge module: optional per spec — skipped for MVP (spec: do not delay for Forge).
