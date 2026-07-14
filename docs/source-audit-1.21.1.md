# SeedBlend Source Audit — Minecraft 1.21.1 (Mojang mappings)

Milestone 0 deliverable (spec §24, §26). Evidence gathered from Vineflower-decompiled
1.21.1 sources via `./gradlew :fabric:genSources` (Loom, official Mojang mappings) and
verified by compiling the mod's mixins against the real classes (Loom refmap resolution
confirms every target).

## Answers to the 15 required questions (spec §26)

### 1. Earliest reliable hook for replacing the seed before `RandomState` and structure state are built

The seed is consumed in `MinecraftServer#createLevels(ChunkProgressListener)`:

```java
// MinecraftServer.java:359-365
protected void createLevels(ChunkProgressListener chunkProgressListener) {
    ...
    long l = this.worldData.worldGenOptions().seed();   // ← first world-gen consumption
    long m = BiomeManager.obfuscateSeed(l);
```

`RandomState.create` and `ChunkGeneratorStructureState` are constructed inside
`ChunkMap`/`ServerLevel` construction, downstream of `createLevels`. Both loaders expose
an event that fires strictly before `createLevels` on the server thread:

- Fabric: `ServerLifecycleEvents.SERVER_STARTING` (fires in `runServer` before `initServer` → `loadLevel`)
- NeoForge: `ServerAboutToStartEvent` (fires before `loadLevel`)

SeedBlend replaces the seed there by swapping `PrimaryLevelData.worldOptions`
(`private final WorldOptions worldOptions;` — PrimaryLevelData.java:47) through a
`@Mutable @Accessor` mixin, constructing `new WorldOptions(newSeed, generateStructures,
generateBonusChest)` (public ctor confirmed, WorldOptions.java:27). No vanilla mixin is
required for the lifecycle itself — only the accessor.

### 2. Does the selected hook work identically in dedicated and integrated servers?

Yes. Both events are fired from common `MinecraftServer` startup paths on both server
types; `createLevels` is shared. Verified by code path inspection; the integrated server
uses the same `PrimaryLevelData`.

### 3. Exact completed chunk-status threshold in 1.21.1

`ChunkStatus.FULL` is the only status whose `ChunkType` is `LEVELCHUNK`:

```java
// ChunkStatus.java:31
public static final ChunkStatus FULL = register("full", SPAWN, FINAL_HEIGHTMAPS, ChunkType.LEVELCHUNK);
```

Serialized as `Status: "minecraft:full"` (`ChunkSerializer.write` line 284 writes the
registry key via `BuiltInRegistries.CHUNK_STATUS.getKey(...)`). Every earlier status
(`empty`, `structure_starts`, `structure_references`, `biomes`, `noise`, `surface`,
`carvers`, `features`, `initialize_light`, `light`, `spawn`) is `PROTOCHUNK` =
generation-incomplete. SeedBlend treats exactly `minecraft:full` (namespaced or bare) as
complete.

### 4. Can chunk NBT be modified before decoding through loader events, or is a vanilla mixin required?

A vanilla mixin is required. Neither Fabric API nor NeoForge exposes a pre-decode chunk
NBT event at the right stage in 1.21.1 (NeoForge's `ChunkDataEvent.Load` receives the
tag but fires per-loader and is not available cross-loader). SeedBlend uses one shared
mixin into `ChunkMap#readChunk`:

```java
// ChunkMap.java:906-907
private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos chunkPos) {
    return this.read(chunkPos).thenApplyAsync(optional -> optional.map(this::upgradeChunkTag), Util.backgroundExecutor());
}
```

Injecting at RETURN and chaining `.thenApply` transforms the tag **after** DataFixer
upgrade and **before** `isChunkDataValid` / `ChunkSerializer.read`
(`ChunkMap.scheduleChunkLoad`, lines 540-552). Mapping the optional to `empty()` makes
vanilla treat the chunk as absent and regenerate it — exactly the spec §10 discard
behavior, with no scheduler involvement.

### 5. Does vanilla preserve or remove synthetic `blending_data` after loading and saving?

Preserved while blending is still pending. Flow:

- Read: `ChunkSerializer.read` line 155 parses `blending_data` via `BlendingData.CODEC`
  into the chunk's `blendingData` field.
- Promotion: `LevelChunk(ServerLevel, ProtoChunk, PostLoadProcessor)`
  (LevelChunk.java:118) passes `protoChunk.getBlendingData()` into the super constructor
  — data survives ProtoChunk → LevelChunk.
- Write: `ChunkSerializer.write` lines 285-287 re-encode `chunkAccess.getBlendingData()`
  whenever non-null.

Vanilla clears blending data only when the blending pass over a region completes
(`Blender`/`BlendingData` mark chunks as blended once new neighbors finish). SeedBlend
additionally re-ensures the compound on save for old completed chunks
(`persistSyntheticBlendingData`) as defense in depth.

### 6. Which serializer owns `blending_data` in 1.21.1?

`net.minecraft.world.level.chunk.storage.ChunkSerializer` (read line 155, write line
287). Note for the 26.1 port: this moved to `SerializableChunkData` in 1.21.2+.

### 7. What happens when an old chunk with blending data is surrounded entirely by other old chunks?

Nothing. `Blender.of(WorldGenRegion)` runs only during **new** chunk generation; it
returns `Blender.EMPTY` unless `worldGenRegion.isOldChunkAround(center, HEIGHT_BLENDING_RANGE_CHUNKS)`
finds blending data nearby, and it only reads `BlendingData` from neighbors of a chunk
being generated. Old chunks are never regenerated, so their blending data is inert until
a new chunk generates within blending range. Fully-enclosed old regions simply carry a
few extra bytes of NBT.

### 8. Which world-generation systems read the canonical seed after server initialization?

From `worldData.worldGenOptions().seed()` / `ServerLevel#getSeed`:
slime-chunk RNG (`WorldgenRandom.seedSlimeChunk`), structure placement
(`ChunkGeneratorStructureState.levelSeed`, field line 38), feature/decoration RNG,
`BiomeManager.obfuscateSeed` (biome noise lookup), random sequences
(`RandomSequences`), raids, and `/seed`. All are constructed or invoked per-level after
`createLevels`, which is why the swap must happen before it (see Q1).

### 9. Which seed-dependent behaviors in existing chunks change after a canonical reseed?

Slime chunks; future structure placement in ungenerated regions of ALL dimensions
(including Nether/End which are not blended); ore/feature RNG for any chunk still below
FULL; mob-spawn scattering that hashes the seed; any mod or command reading the world
seed. The `/seedblend plan` output warns about all of this (spec §5).

### 10. Can a reliable generation-only seed mode be implemented later without thread-local behavior?

Yes, in principle: `RandomState.create`, `ChunkGeneratorStructureState.createForNormal`,
and `BiomeManager.obfuscateSeed` all receive the seed as an explicit parameter inside
`createLevels`/`ChunkMap` construction, so a generation-only mode could substitute the
seed at those three construction sites while leaving `WorldOptions` untouched. It
requires auditing every other consumer in Q8 first (random sequences, raids, slime
chunks) to decide which side of the split each belongs to — deferred per spec §5.

### 11. Which custom generators invoke vanilla `Blender`?

`NoiseBasedChunkGenerator` is the only vanilla generator that does
(`doFill`/`buildSurface` pass `Blender.of(region)`). `FlatLevelSource` and
`DebugLevelSource` do not. Datapack `minecraft:noise` dimensions use
`NoiseBasedChunkGenerator` and are compatible. SeedBlend gates blending injection on
`generator instanceof NoiseBasedChunkGenerator` (config `allowCustomNoiseGenerators`
overrides for mods that subclass or wrap correctly).

### 12. Can old incomplete chunks be safely treated as absent without leaving stale POI or entity data?

Yes. POI (`poi/` region files) is only written for chunks that reached
`ChunkStatus.FULL`-adjacent stages and is keyed by section; entities live in separate
`entities/` region files written at FULL. A pre-FULL chunk has neither. Discarding the
tag in `readChunk` regenerates through the normal pipeline which overwrites the chunk
file on next save. (Verified: `PoiManager` persistence is driven by loaded sections, and
`ChunkSerializer.read` line 153 checks `getChunkTypeFromTag` before entity attachment.)

### 13. Which mixin points are least likely to conflict with C2ME and other chunk-performance mods?

The chosen set avoids schedulers, futures ordering, and per-block work entirely:

| Target | Injection | Why safe |
|---|---|---|
| `ChunkMap#readChunk` | RETURN, wrap future | pure data transform on the read result; C2ME re-schedules but keeps the method contract |
| `ChunkSerializer.read` | RETURN | stamps the duck field only |
| `ChunkSerializer.write` | RETURN | augments the produced tag only |
| `LevelChunk(ServerLevel, ProtoChunk, ...)` | TAIL | copies one long |
| `ChunkAccess` | interface + `@Unique` field | additive |
| `PrimaryLevelData` | accessor | additive |

No `@Redirect`/`@Overwrite` anywhere; all injections are additive `@Inject`.

### 14. Does Forge support require substantial divergence from NeoForge?

Forge 1.21.1 retains `ChunkDataEvent` and a Mixin-capable toolchain, so the common core
would port, but its FML/mods.toml formats and event classes differ enough that a third
loader module (~duplicated glue, separate CI matrix) is required. Per spec §2 Forge is
optional and must not delay Fabric/NeoForge — deferred post-MVP.

### 15. What behavior remains when the mod is removed after one or more reseeds?

The canonical seed persists in `level.dat` (vanilla saves the swapped `WorldOptions`),
so the world keeps generating with the newest seed. Injected `blending_data` on old
chunks remains valid vanilla data — native blending keeps working for already-marked
chunks. `seedblend` chunk compounds are unknown NBT that vanilla silently preserves.
What stops: epoch classification (chunks from older epochs no longer get blending data
injected on first post-removal load), future-epoch protection, and the commands.
Removal is safe; re-installing resumes where it left off because state.json and chunk
epochs are still on disk.

## Recorded mixin targets

```text
net.minecraft.server.level.ChunkMap#readChunk(ChunkPos)Ljava/util/concurrent/CompletableFuture; @Inject(RETURN, cancellable)
net.minecraft.world.level.chunk.storage.ChunkSerializer#read(ServerLevel,PoiManager,RegionStorageInfo,ChunkPos,CompoundTag)ProtoChunk @Inject(RETURN)
net.minecraft.world.level.chunk.storage.ChunkSerializer#write(ServerLevel,ChunkAccess)CompoundTag @Inject(RETURN)
net.minecraft.world.level.chunk.LevelChunk#<init>(ServerLevel,ProtoChunk,LevelChunk$PostLoadProcessor)V @Inject(TAIL)
net.minecraft.world.level.chunk.ChunkAccess — @Unique field + duck interface
net.minecraft.world.level.storage.PrimaryLevelData#worldOptions — @Mutable @Accessor
```

Proof-of-concept confirmation (spec §24 M0.4-M0.6) is automated by the headless
integration fixture (`docs/reseed-lifecycle.md`, `gametest/`): synthetic `blending_data`
on one completed chunk makes adjacent new chunks blend; injecting into pre-FULL chunks
is avoided entirely because they are discarded instead.
