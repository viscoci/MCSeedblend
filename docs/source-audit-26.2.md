# SeedBlend Source Audit — Minecraft 26.2

Delta audit for the 26.2 release line (`mc26.2/`). Baseline: `source-audit-26.1.md`.

## Result: zero source changes from 26.1

The 26.1 sources compile unmodified against Minecraft 26.2 (NeoForm `26.2-1`,
NeoForge `26.2.0.15-beta`, Fabric Loader 0.19.3, Fabric API `0.154.2+26.2`,
Loom 1.17 / ModDevGradle 2.0.141, Java 25, Gradle 9.5). Every API surface SeedBlend
touches is unchanged between 26.1.2 and 26.2:

- `WorldGenSettings` SavedData still carries the canonical seed; `MinecraftServer
  #getWorldGenSettings()` and the pre-`createLevels` loader-event timing are unchanged.
- `SerializableChunkData` keeps the same `parse` / `read` / `copyOf` / `write`
  signatures and still owns `blending_data` (`BlendingData.Packed`, same codec keys).
- `ChunkMap#readChunk(ChunkPos)` → `CompletableFuture<Optional<CompoundTag>>` unchanged.
- `LevelChunk(ServerLevel, ProtoChunk, PostLoadProcessor)` promotion ctor unchanged.
- `ChunkStatus.FULL` / `minecraft:full` threshold unchanged.
- Reworked NBT Optional API, `Identifier`, permission-set commands — all as in 26.1.

Mixin-target validity is a runtime property (26.x has no refmap remapping), so
compilation alone is not proof; verification is the live fixture below.

## Verification status

- `mc26.2` builds `seedblend-fabric-26.2-0.1.0.jar` and `seedblend-neoforge-26.2-0.1.0.jar`;
  27 unit tests pass.
- The RCON integration fixture (`fixture/reseed-fixture.ps1 -ProjectSubdir mc26.2`)
  passes all 44 assertions against live 26.2 dedicated servers on both loaders —
  confirming every mixin applies and the full reseed lifecycle works on 26.2.

## Note

NeoForge for 26.2 is still published as beta (`26.2.0.x-beta`). Bump
`neoforge_version` in `mc26.2/gradle.properties` when a stable build lands; no code
changes are expected.
