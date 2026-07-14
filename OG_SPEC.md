# SeedBlend

## Automatic World Reseeding with Native Terrain Blending

### Project status

Initial open-source implementation specification.

### Proposed license

MIT License.

---

# 1. Summary

SeedBlend is a server-side Minecraft Java Edition mod that allows an existing world to begin generating new chunks using a different seed while preserving already-generated chunks.

When new chunks generate adjacent to chunks created under an older seed, SeedBlend automatically marks the older chunks as blending sources. Minecraft’s native terrain-blending system then adjusts the new terrain so that elevations, underground density, caves, and biomes transition more naturally across the boundary.

The mod must not regenerate, reshape, or directly edit blocks in completed existing chunks.

The intended workflow is:

1. Generate and play a world using seed A.
2. Run a SeedBlend command to stage seed B.
3. Restart the server or single-player world.
4. Existing completed chunks remain unchanged.
5. Newly generated chunks use seed B.
6. New chunks adjacent to old chunks are blended using Minecraft’s native blending system.
7. Chunks sufficiently far from the boundary generate normally from seed B.
8. The process can be repeated later with seed C, seed D, and so on.

---

# 2. Initial platform targets

## Required first release

* Minecraft Java Edition 1.21.1
* Java 21
* Fabric
* NeoForge
* Dedicated servers
* Integrated single-player servers

## Optional first-release target

* Forge 1.21.1, provided it can share the same common implementation without substantial duplicated core logic.

Do not delay Fabric and NeoForge support solely to support Forge.

## Later release line

After the 1.21.1 implementation is stable, port it to the current Minecraft release line, initially 26.1.x.

Each Minecraft version and loader must have its own artifact. Do not attempt to create one universal JAR for multiple Minecraft versions.

Example artifact names:

```text
seedblend-fabric-1.21.1-0.1.0.jar
seedblend-neoforge-1.21.1-0.1.0.jar
seedblend-forge-1.21.1-0.1.0.jar
```

---

# 3. Build architecture

Use a Gradle multi-project repository:

```text
seedblend/
├── common/
├── fabric/
├── neoforge/
├── forge/              # Optional
├── gametest/
├── build.gradle
├── settings.gradle
├── gradle.properties
├── LICENSE
└── README.md
```

Use Mojang mappings across loaders where supported to minimize source divergence.

Prefer the standard MultiLoader project structure with common sources and thin loader-specific modules. The MultiLoader Template supports compiling common code for multiple loaders without requiring a third-party runtime library.

## Runtime dependencies

### Fabric

* Fabric Loader
* Fabric API

### NeoForge

* NeoForge

### Forge

* Forge, if implemented

Do not add Architectury API, Cloth Config, or another runtime abstraction dependency unless a concrete need is demonstrated.

Mixin is permitted because it is already part of the supported loader toolchains.

---

# 4. Core design decisions

## 4.1 Use vanilla blending

The MVP must use Minecraft’s native `Blender` and `BlendingData` implementation.

Do not implement custom block interpolation, terrain sculpting, or a replacement noise generator.

Minecraft treats a chunk as old-generation terrain when its serialized data contains `blending_data`. The compound contains `min_section` and `max_section`, defining the vertical range used for blending.

Example:

```snbt
blending_data: {
    min_section: -4,
    max_section: 20
}
```

The values must come from the dimension’s actual minimum section and exclusive maximum section. Do not hard-code Overworld values.

## 4.2 Restart-required reseeding

Do not support changing seeds while the world is actively generating chunks.

A reseed is staged while the server is running and applied during the next startup, before world-generation state is initialized.

This avoids stale instances of:

* `RandomState`
* `ChunkGeneratorStructureState`
* biome-generation state
* structure-placement state
* noise routers
* random-sequence state
* chunk-generation tasks already in flight

## 4.3 Generation epochs

Every chunk must be associated with the seed-generation epoch under which it was created.

Example:

```text
Epoch 0: original world seed
Epoch 1: first replacement seed
Epoch 2: second replacement seed
```

A chunk whose epoch is less than the active world epoch is an old-generation chunk and can act as a blending source.

New chunks are assigned the current active epoch.

Chunk epochs are immutable. Loading and saving an old chunk must not update it to the current epoch.

## 4.4 No full-world scan

The normal reseed workflow must not require scanning every region file.

Chunks are classified when Minecraft naturally loads their NBT.

This gives approximately constant additional work per loaded chunk and scales to large worlds.

An optional offline `materialize` operation that scans region files may be implemented later, but is not part of the MVP.

---

# 5. Important behavioral limitation

The initial implementation may use Minecraft’s canonical world seed as the newly selected seed.

This means the seed change can affect more than newly generated terrain, including seed-dependent behavior in already-generated areas.

Potential examples include:

* slime chunk calculations
* future structure placement
* unexplored chunks in the Nether
* unexplored chunks in the End
* seed-based mod behavior
* commands or tools that read the world seed

The command workflow must clearly warn the administrator of this.

A later `generation-only` mode may preserve the original canonical seed while supplying a separate seed exclusively to chunk generation. That mode must not be implemented until every relevant world-generation seed consumer has been audited.

---

# 6. Supported dimensions

## MVP blending support

Enable automatic blending by default only for:

```text
minecraft:overworld
```

The generator must also be compatible with vanilla noise-based blending.

## Default unsupported dimensions

* `minecraft:the_nether`
* `minecraft:the_end`
* flat worlds
* debug worlds
* custom chunk generators that bypass vanilla `Blender`
* dimensions with incompatible vertical or biome-generation behavior

Manually injecting blending metadata into inappropriate dimensions can produce invalid terrain, particularly in the End. Therefore, unsupported dimensions must not receive synthetic `blending_data` by default.

The global seed may still affect future generation in those dimensions. The preflight command must warn about this.

## Future support

Custom dimensions may opt into blending through configuration after generator compatibility has been verified.

---

# 7. Persistent state

Store mod state inside the save directory:

```text
<world>/seedblend/state.json
```

Suggested schema:

```json
{
  "schemaVersion": 1,
  "worldFingerprint": "sha256-value",
  "mode": "canonical_world_seed",
  "activeEpoch": 1,
  "activeSeed": 9876543210,
  "previousSeed": 123456789,
  "pendingTransaction": null,
  "lastSuccessfulStartupEpoch": 1
}
```

A pending transaction:

```json
{
  "transactionId": "4b5d3f...",
  "targetSeed": 9876543210,
  "targetEpoch": 1,
  "createdBy": "ServerOwner",
  "state": "STAGED"
}
```

## File safety

Writes must use an atomic replacement pattern:

```text
state.json.tmp
state.json
state.json.bak
```

Required write procedure:

1. Serialize the new state to `state.json.tmp`.
2. Flush and close it.
3. Move the current file to `state.json.bak`.
4. Atomically move the temporary file to `state.json` where supported.
5. Recover from the backup if parsing the active file fails.

## World fingerprint

The state file must be bound to the save it belongs to.

The fingerprint may include:

* original seed
* level name
* world creation metadata
* save path identifier

If a state file is copied to an unrelated world, startup must stop with a clear error rather than silently reseeding it.

---

# 8. Chunk metadata

Add a namespaced compound to each serialized chunk:

```snbt
seedblend: {
    schema: 1,
    generation_epoch: 0L
}
```

Using a compound makes future metadata additions possible.

## Epoch interpretation

```text
Missing metadata:
    Treat as epoch 0.

chunk epoch == active epoch:
    Current-generation chunk.

chunk epoch < active epoch:
    Old-generation chunk.

chunk epoch > active epoch:
    Invalid rollback or damaged state.
    Refuse to load the world without an explicit recovery operation.
```

Missing metadata is treated as epoch 0 so that worlds created before installing SeedBlend work without migration.

---

# 9. Chunk loading algorithm

Hook chunk NBT after it has been read from disk but before vanilla converts it into a `ChunkAccess`, `ProtoChunk`, or `LevelChunk`.

The common implementation should operate on the serialized chunk compound whenever possible.

Conceptual algorithm:

```java
ChunkReadResult processChunkNbt(
    DimensionContext dimension,
    CompoundTag chunkTag,
    SeedBlendWorldState worldState
) {
    long activeEpoch = worldState.activeEpoch();
    long chunkEpoch = readSeedBlendEpoch(chunkTag).orElse(0L);
    ChunkStatus status = readChunkStatus(chunkTag);

    if (chunkEpoch > activeEpoch) {
        throw new SeedBlendEpochRollbackException(
            chunkEpoch,
            activeEpoch
        );
    }

    if (chunkEpoch < activeEpoch) {
        if (!status.isOrAfter(ChunkStatus.FULL)) {
            return ChunkReadResult.DISCARD_AND_REGENERATE;
        }

        if (dimension.blendingSupported()) {
            ensureVanillaBlendingData(
                chunkTag,
                dimension.minSection(),
                dimension.maxSection()
            );
        }
    }

    ensureSeedBlendMetadata(chunkTag, chunkEpoch);

    return ChunkReadResult.LOAD;
}
```

## Existing vanilla blending data

If a chunk already contains valid `blending_data`, preserve it.

Do not replace:

* calculated height arrays
* existing vertical bounds
* data created during a Minecraft version upgrade

Only synthesize the compound if it is absent.

Malformed blending data should generate a warning and be replaced only when it cannot be decoded safely.

---

# 10. Incomplete chunks

Never inject `blending_data` into an unfinished chunk.

Adding blending metadata to chunks that have not reached the required generation status has caused biome-access crashes in existing tools.

If an incomplete chunk belongs to an older epoch:

1. Treat it as nonexistent.
2. Discard its incomplete generation state.
3. Regenerate it completely using the active seed and epoch.
4. Do not preserve partial terrain from the older seed.

This behavior must cover all statuses below the completed status required by the target Minecraft version.

The exact threshold must be verified against the target version’s chunk serializer. Do not rely only on the textual name `full` without inspecting the target source.

---

# 11. New chunk behavior

Chunks created without serialized SeedBlend metadata are considered new in-memory chunks.

Add a mixin-backed interface to `ChunkAccess`:

```java
public interface SeedBlendChunkEpochAccess {
    long seedblend$getGenerationEpoch();
    void seedblend$setGenerationEpoch(long epoch);
    boolean seedblend$hasAssignedGenerationEpoch();
}
```

For a new chunk:

```text
generation epoch = current active epoch
```

Assign it before the chunk can be serialized.

When a chunk is saved:

```java
long epoch = chunk.seedblend$hasAssignedGenerationEpoch()
    ? chunk.seedblend$getGenerationEpoch()
    : activeWorldEpoch;
```

Write:

```snbt
seedblend: {
    schema: 1,
    generation_epoch: <epoch>
}
```

If the chunk is completed, belongs to an older epoch, and is in a supported dimension, ensure that its serialized form still contains valid `blending_data`.

---

# 12. Seed application lifecycle

## Staging

The seed is not applied immediately.

A command writes a pending transaction to `state.json`.

## Startup

During the next startup:

1. Load and validate SeedBlend state.
2. Detect the pending transaction.
3. Verify the world fingerprint.
4. Select the pending seed and pending epoch.
5. Inject the selected seed after level data is decoded but before world-generation objects are constructed.
6. Construct all world-generation state using the selected seed.
7. Start the server.
8. Verify the server levels are using the expected seed.
9. Mark the transaction active.
10. Save the finalized state atomically.

## Failed startup

If startup fails before finalization:

* Preserve the pending transaction.
* Do not increment the epoch again.
* Retry the same transaction during the next startup.
* Log the failure state clearly.

The operation must be idempotent.

## Hook requirement

The seed override must occur before construction of objects derived from the world seed.

The agent must audit the target version and identify the earliest stable hook around world-data loading.

Do not:

* modify `level.dat` directly while it is open
* replace generator state after levels are created
* mutate private seed fields after chunk generation begins
* use a world-generation-thread conditional seed override

The normal Minecraft save process should eventually persist the updated canonical seed to `level.dat`.

---

# 13. Commands

All modifying commands require permission level 4.

## Status

```text
/seedblend status
```

Output:

* active epoch
* active seed
* pending seed
* pending transaction ID
* operating mode
* supported blending dimensions
* whether restart is required
* warning if `level.dat` and SeedBlend state disagree

## Plan a reseed

```text
/seedblend plan <seed>
```

The seed argument may be:

* signed 64-bit integer
* Minecraft-compatible textual seed

The command performs preflight checks and returns a temporary plan token.

Example response:

```text
SeedBlend reseed plan

Current seed: 123456789
New seed: 9876543210
Current epoch: 0
New epoch: 1

Existing Overworld chunks will be preserved.
Future Overworld chunks will use native terrain blending.

Warning:
The canonical world seed will change.
Future Nether and End chunks will also use the new seed, but SeedBlend
does not currently blend those dimensions.

A complete world backup is strongly recommended.

Commit with:
/seedblend commit 73A91C
```

## Commit the plan

```text
/seedblend commit <plan-token>
```

Effects:

* validates that the plan is still current
* writes the pending transaction
* reports that a restart is required

It must not alter active world-generation state.

## Cancel

```text
/seedblend cancel
```

Cancels a staged transaction that has not yet been applied.

## Inspect a chunk

```text
/seedblend inspect chunk <chunk-x> <chunk-z> [dimension]
```

Output:

* dimension
* chunk status
* serialized generation epoch
* active epoch
* whether it is considered old
* whether blending data is present
* whether synthetic blending would be injected
* minimum and maximum blending sections

The command must not generate an absent chunk.

## Verify

```text
/seedblend verify
```

Checks:

* state-file consistency
* current seed consistency
* pending transaction consistency
* generator support
* invalid future-epoch chunks encountered during this runtime
* malformed SeedBlend metadata encountered during this runtime

---

# 14. Configuration

Use a small JSON configuration file and Minecraft’s bundled JSON support. Do not add a configuration-library dependency for the MVP.

```text
config/seedblend.json
```

Example:

```json
{
  "supportedDimensions": [
    "minecraft:overworld"
  ],
  "discardIncompleteOldChunks": true,
  "persistSyntheticBlendingData": true,
  "requirePlanToken": true,
  "warnOnUnsupportedDimensions": true,
  "allowCustomNoiseGenerators": false,
  "diagnosticLogging": false
}
```

Changes affecting seed behavior should require a restart.

---

# 15. Loader-specific responsibilities

## Common module

Contains:

* state model
* atomic state-file storage
* seed parsing
* transaction logic
* epoch comparison
* chunk NBT transformation
* blending policy
* command implementation where Brigadier types are common
* diagnostics
* validation
* unit tests

## Fabric module

Contains:

* Fabric entrypoint
* Fabric command registration
* server lifecycle integration
* Fabric mixin configuration
* Fabric-specific accessors or hooks

Fabric API exposes event hooks intended to reduce direct mixin use where appropriate.

## NeoForge module

Contains:

* `@Mod` entrypoint
* NeoForge command registration
* server lifecycle integration
* NeoForge mixin configuration
* NeoForge-specific accessors or hooks

## Forge module

Contains the equivalent Forge integration if implemented.

Forge exposes chunk-data load and save integration points, but the exact timing must be verified to ensure the chunk NBT can be modified before vanilla decoding uses it.

## Hook policy

Prefer public loader events when they occur at the correct lifecycle stage.

Use common mixins when:

* both loaders need the same vanilla hook
* the loader event fires too late
* the event only exposes an already-decoded chunk
* the seed must be replaced before generator state construction

Avoid loader-specific copies of the terrain-blending algorithm.

---

# 16. Compatibility requirements

## Server-side installation

The mod must work when installed only on the dedicated server.

Clients should not need SeedBlend to connect.

The same JAR may contain integrated-server support for single player, but no custom client networking is required.

## Chunk pregenerators

SeedBlend should work with chunk-pregeneration mods provided:

* the reseed transaction has been applied before pregeneration starts
* pregeneration uses the normal server chunk generator
* the generator has not replaced vanilla blending

## World-generation mods

Compatibility categories:

### Expected compatible

Generators that:

* use normal Minecraft noise generation
* invoke vanilla `Blender`
* retain standard chunk serialization

### Partial compatibility

Generators that use the world seed correctly but do not use vanilla blending.

New terrain may use the new seed, but boundaries may remain abrupt.

### Unsupported

Generators that:

* completely replace chunk NBT serialization
* bypass the standard chunk pipeline
* assume the world seed never changes
* replace or disable vanilla blending
* use custom dimension-specific seed state that SeedBlend cannot update

Log a warning rather than claiming support when compatibility cannot be established.

## Performance mods

Avoid mixins into chunk scheduling internals so compatibility with C2ME and similar mods is more likely.

Prefer:

* world-load seed hooks
* chunk serialization hooks
* chunk deserialization hooks

Avoid:

* replacing task schedulers
* blocking chunk-generation futures
* synchronously loading neighboring chunks
* global region scans during generation

---

# 17. Thread-safety requirements

Chunk generation and chunk I/O may occur concurrently.

Use an immutable runtime context:

```java
public record SeedBlendRuntimeState(
    long activeSeed,
    long activeEpoch,
    SeedBlendMode mode,
    Set<ResourceKey<Level>> blendingDimensions
) {}
```

Publish it once before levels begin loading.

Requirements:

* no mutable global maps accessed unsafely by world-generation threads
* no synchronous state-file writes from generation threads
* no loading neighboring chunks for classification
* no mutation of shared NBT instances
* no thread-local seed substitution
* no changing the active epoch until the next server restart

Diagnostic counters may use `LongAdder`.

---

# 18. Logging and diagnostics

Use SLF4J.

Required startup log:

```text
[SeedBlend] Active epoch: 1
[SeedBlend] Active generation seed: 9876543210
[SeedBlend] Mode: canonical_world_seed
[SeedBlend] Native blending enabled for minecraft:overworld
[SeedBlend] Nether and End blending are disabled
```

Runtime counters:

* chunks loaded with missing SeedBlend metadata
* old completed chunks identified
* synthetic blending compounds injected
* old incomplete chunks discarded
* new chunks assigned current epoch
* malformed metadata encountered
* unsupported generators encountered
* future-epoch chunks rejected

Do not include analytics or external telemetry.

---

# 19. Safety requirements

## Backups

The mod must prominently recommend a complete backup before committing a reseed.

The mod does not need to create a full automatic backup in the MVP because saves may be very large.

It should create small backups of:

* SeedBlend state
* relevant level metadata where safely available

## Fail closed

Refuse startup when:

* the world fingerprint does not match
* an active chunk has an epoch newer than the active world epoch
* the state file is unrecoverably malformed
* the pending seed cannot be applied before generator initialization
* the loaded world seed differs from the requested seed after initialization

## No silent fallback

Do not continue with the original seed after reporting that a pending reseed was applied.

Either the selected seed is active or startup must fail with a clear diagnostic.

---

# 20. Testing strategy

## Unit tests

Test:

* state serialization
* atomic state recovery
* seed parsing
* epoch comparisons
* missing metadata behavior
* malformed metadata behavior
* future-epoch rejection
* incomplete-chunk discard decisions
* blending-compound creation
* vertical-section calculations
* dimension policy
* transaction idempotence

## GameTests

NeoForge provides GameTest integration for Minecraft 1.21.1.

Create equivalent integration tests for both required loaders.

## Headless integration fixture

Automate the following test:

1. Create a world with seed A.
2. Generate a rectangular area.
3. Record block and heightmap hashes for existing chunks.
4. Stage seed B.
5. Restart.
6. Generate chunks around the existing boundary.
7. Save and restart again.
8. Verify persisted epochs and blending metadata.

## Required assertions

### Existing chunks

* Existing completed chunks retain their original generation epoch.
* SeedBlend does not directly replace their block sections.
* Block entities and entities remain present.
* Existing vanilla `blending_data` is preserved.

### New chunks

* Newly generated chunks receive the active epoch.
* Newly generated chunks use seed B.
* Chunks near old terrain receive vanilla blending influence.
* Chunks outside vanilla’s blending range converge to ordinary seed-B generation.

### Incomplete chunks

* Old incomplete chunks are never given `blending_data`.
* Old incomplete chunks are regenerated using seed B.
* No biome-before-biomes or equivalent generation crash occurs.

### Multiple reseeds

Test:

```text
Seed A, epoch 0
Seed B, epoch 1
Seed C, epoch 2
```

Chunks from epochs 0 and 1 must both be considered old when epoch 2 is active.

### Geometry cases

Test:

* straight generated boundary
* diagonal boundary
* isolated generated island
* enclosed ungenerated hole
* negative chunk coordinates
* region-file boundary
* world-border-adjacent chunks

### Failure recovery

Test crashes or forced termination:

* after staging
* during startup before finalization
* after generator initialization
* before the state file is atomically replaced

Restarting must either complete the same transaction or fail safely. It must never create an accidental extra epoch.

---

# 21. Acceptance criteria

The MVP is complete when all of the following are true:

1. A clean checkout builds Fabric and NeoForge artifacts.
2. The mod can be installed server-side without client installation.
3. Installing the mod alone makes no world changes.
4. A reseed requires an explicit plan and commit.
5. A staged reseed takes effect only after restart.
6. New chunks use the selected seed.
7. Existing completed chunks retain their blocks.
8. Old completed Overworld chunks automatically become vanilla blending anchors when loaded.
9. Old incomplete chunks are discarded and regenerated.
10. No full-world region scan is required.
11. Multiple sequential reseeds work.
12. State recovery is idempotent after an interrupted startup.
13. Far-from-boundary terrain is consistent with ordinary generation using the active seed.
14. Unsupported dimensions and generators produce clear warnings.
15. The repository contains tests, contributor documentation, and an open-source license.

---

# 22. Explicit non-goals for the MVP

Do not implement:

* live seed changes without restarting
* Minecraft Bedrock support
* direct editing of existing terrain
* custom terrain interpolation
* configurable vanilla blending radius
* seamless structures crossing old/new boundaries
* Nether blending
* End blending
* automatic deletion of arbitrary completed chunks
* retroactive ore or feature generation
* automatic full-world backups
* a graphical configuration screen
* a single JAR covering several Minecraft versions

---

# 23. Known limitations to document

Even with native blending, the following may still occur:

* rivers that do not align perfectly
* caves or aquifers that connect imperfectly
* structures cut off at generation boundaries
* roads, villages, or modded structures that stop abruptly
* biome transitions that are visible
* seed-dependent mechanics changing in existing chunks
* unblended Nether or End boundaries
* incompatibility with custom generators

The mod should promise naturalized transitions, not mathematically seamless world merging.

---

# 24. Suggested implementation milestones

## Milestone 0: Source audit and proof of concept

Before constructing the full project:

1. Locate the exact 1.21.1 world-data seed initialization path.
2. Confirm the earliest safe seed replacement hook.
3. Locate chunk NBT parsing and serialization paths.
4. Manually inject `blending_data` into one completed test chunk.
5. Confirm a neighboring new chunk uses vanilla blending.
6. Confirm incomplete chunks are unsafe to mark.
7. Record all required mixin targets in an architecture note.

Deliverable:

```text
docs/source-audit-1.21.1.md
```

## Milestone 1: Multi-loader skeleton

* common module
* Fabric module
* NeoForge module
* CI builds
* version metadata
* basic server startup logs

## Milestone 2: State and seed transactions

* state JSON
* atomic writes
* plan and commit commands
* pending startup transaction
* canonical seed replacement
* startup verification

## Milestone 3: Chunk epochs

* chunk access interface
* read existing metadata
* baseline epoch assignment
* write current epoch
* reject future epochs
* preserve epoch through saves

## Milestone 4: Automatic blending

* identify old completed chunks
* inject native blending data before decode
* preserve existing blending data
* discard old incomplete chunks
* validate vertical section bounds

## Milestone 5: Diagnostics

* status command
* inspect command
* verify command
* counters
* generator compatibility warnings

## Milestone 6: Testing and release

* unit tests
* GameTests
* automated headless reseed fixture
* Fabric and NeoForge compatibility checks
* README
* contributor guide
* release artifacts

---

# 25. Required repository documentation

```text
README.md
LICENSE
CONTRIBUTING.md
CHANGELOG.md
docs/
├── architecture.md
├── source-audit-1.21.1.md
├── world-safety.md
├── compatibility.md
├── chunk-metadata.md
└── reseed-lifecycle.md
```

The README must include:

* prominent backup warning
* supported Minecraft versions
* supported loaders
* server-side installation instructions
* command workflow
* explanation of epochs
* limitations
* unsupported dimensions
* removal behavior
* issue-report template requirements

---

# 26. Questions the implementation agent must resolve

The agent must explicitly answer these during Milestone 0:

1. What is the earliest reliable hook for replacing the seed before `RandomState` and structure state are built?
2. Does the selected hook work identically in dedicated and integrated servers?
3. What is the exact completed chunk-status threshold in 1.21.1?
4. Can chunk NBT be modified before decoding through loader events, or is a vanilla mixin required?
5. Does vanilla preserve or remove synthetic `blending_data` after loading and saving?
6. Which serializer owns `blending_data` in 1.21.1?
7. What happens when an old chunk with blending data is surrounded entirely by other old chunks?
8. Which world-generation systems read the canonical seed after server initialization?
9. Which seed-dependent behaviors in existing chunks change after a canonical reseed?
10. Can a reliable generation-only seed mode be implemented later without thread-local behavior?
11. Which custom generators invoke vanilla `Blender`?
12. Can old incomplete chunks be safely treated as absent without leaving stale POI or entity data?
13. What mixin points are least likely to conflict with C2ME and other chunk-performance mods?
14. Does Forge support require substantial divergence from NeoForge?
15. What behavior remains when the mod is removed after one or more reseeds?

Do not consider Milestone 0 complete until these answers are recorded.
