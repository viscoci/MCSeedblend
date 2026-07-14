# SeedBlend Source Audit — Minecraft 26.1 (26.1.2, unobfuscated)

Port-level answers to the spec §26 questions, from Vineflower-decompiled 26.1.2 sources
(`mc26.1$ ./gradlew :fabric:genSources`). 26.1 ships unobfuscated (real names), runs on
Java 25, and builds with Gradle 9.1+ (Loom `net.fabricmc.fabric-loom` 1.17, ModDevGradle
2.0.141, NeoForge 26.1.2.x, Fabric Loader 0.19.x). Only differences from
`source-audit-1.21.1.md` are recorded here — everything else carries over.

## Key differences from 1.21.1

### Seed storage: `WorldGenSettings` SavedData (was: WorldOptions inside level.dat)

The canonical seed no longer lives in `PrimaryLevelData`. `MinecraftServer` holds
`private final WorldGenSettings worldGenSettings` (a `SavedData` persisted as the
`world_gen_settings` saved-data file), assigned in the server constructor and exposed
via `getWorldGenSettings()`:

```java
// MinecraftServer.java:427-432
protected void createLevels() {
    ...
    WorldOptions worldOptions = this.worldGenSettings.options();
    long seed = worldOptions.seed();
```

Seed replacement: `@Mutable @Accessor("options")` on `WorldGenSettings`, swap in
`new WorldOptions(seed, generateStructures, generateBonusChest)` (ctor unchanged,
WorldOptions.java:27), then `setDirty()` so the SavedData persists the new seed.
The pre-level loader events (Fabric `SERVER_STARTING` / NeoForge
`ServerAboutToStartEvent`) still fire before `createLevels` — the hook timing is
unchanged.

### Chunk serializer: `SerializableChunkData` record (was: ChunkSerializer statics)

`ChunkSerializer` is gone. `SerializableChunkData` (record) owns `blending_data`
(as `BlendingData.Packed`, codec keys `min_section`/`max_section` unchanged —
SerializableChunkData.java:121, 419; BlendingData.java:401-406):

- `parse(LevelHeightAccessor, PalettedContainerFactory, CompoundTag)` → record (null when `Status` missing)
- `read(ServerLevel, PoiManager, RegionStorageInfo, ChunkPos)` → `ProtoChunk` (instance method)
- `copyOf(ServerLevel, ChunkAccess)` → record; `write()` → `CompoundTag` (runs on a background executor, ChunkMap.java:760-762)

The record does **not** round-trip unknown NBT, so the `seedblend` compound cannot ride
through vanilla serialization. SeedBlend mixes into the record: `parse` captures the
epoch from the raw tag onto the instance (`@Unique` field), `read` stamps it onto the
`ProtoChunk`/wrapped `LevelChunk`, `copyOf` captures the chunk's epoch (assigning the
active epoch to new chunks) plus the dimension policy, and `write` re-appends the
`seedblend` compound and re-ensures `blending_data` for old completed chunks.

### Unchanged hooks

- `ChunkMap#readChunk(ChunkPos)` still returns `CompletableFuture<Optional<CompoundTag>>`
  post-datafix (ChunkMap.java:907) — the tag-transform/discard mixin ports verbatim.
- `LevelChunk(ServerLevel, ProtoChunk, PostLoadProcessor)` promotion ctor unchanged
  (LevelChunk.java:128) — epoch carry-over mixin ports verbatim.
- `ChunkStatus.FULL` is still the only `LEVELCHUNK` status, serialized as
  `minecraft:full` (ChunkStatus.java:32; SerializableChunkData.java:418).
- `ChunkGeneratorStructureState.getLevelSeed()` / `ServerChunkCache.getGeneratorState()`
  for post-start verification — unchanged.
- `ChunkMap extends SimpleRegionStorage` keeps public `read(ChunkPos)` for the
  no-generation inspect command.

### API renames that touch SeedBlend

| 1.21.1 | 26.1 |
|---|---|
| `ResourceLocation`, `ResourceKey#location()` | `Identifier`, `ResourceKey#identifier()` |
| `LevelHeightAccessor#getMinSection()` / `getMaxSection()` (exclusive) | `getMinSectionY()` / `getMaxSectionY()` (**inclusive** — SeedBlend uses `getMaxSectionY() + 1` for the exclusive blending bound) |
| `CompoundTag.contains(key, type)`, `getLong(key) → long` | reworked NBT API: `getLong(key) → Optional<Long>`, `getLongOr/getIntOr/getStringOr`, `getCompound(key) → Optional<CompoundTag>` |
| `source.hasPermission(4)` | `Commands.hasPermission(Commands.LEVEL_OWNERS)` (permission-set API) |
| Loom plugin `fabric-loom`, `mappings`/`modImplementation` | `net.fabricmc.fabric-loom`, no mappings dep, plain `implementation` (unobfuscated) |

### Nether/End safety note

26.1 ships `BlendingDataRemoveFromNetherEndFix` — Mojang actively strips blending data
from those dimensions, confirming the spec §6 rule that synthetic `blending_data` must
never be injected outside the Overworld.

## Verification status

- `mc26.1` builds `seedblend-fabric-26.1-0.1.0.jar` and `seedblend-neoforge-26.1-0.1.0.jar`;
  27 unit tests pass against the 26.1 NBT API.
- The RCON integration fixture (`fixture/reseed-fixture.ps1 -ProjectSubdir mc26.1`)
  drives the same 44-assertion reseed lifecycle against live 26.1 dev servers.
