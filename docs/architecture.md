# SeedBlend Architecture

## Module layout (MultiLoader pattern)

`common/` holds all logic and all mixins, compiled against Mojang-mapped vanilla via
NeoForm. Each loader module (`fabric/`, `neoforge/`) compiles the common **sources**
into its own jar (no runtime library, no jar-in-jar) and contributes only:
entrypoint, config-dir resolution, lifecycle event registration, command registration.

## Data flow

```
                 ┌──────────────── startup ────────────────┐
loader event ──► StartupSeedTransaction.onServerStarting   │  before createLevels
                 │  load config + state.json               │
                 │  verify world fingerprint (fail closed)  │
                 │  apply staged seed → PrimaryLevelData    │  (accessor mixin)
                 │  publish SeedBlendRuntimeState (once)    │
                 └──────────────────────────────────────────┘
                 ┌──────────────── post-start ─────────────┐
loader event ──► StartupSeedTransaction.onServerStarted    │
                 │  verify every level's structure-state    │
                 │    seed == expected (no silent fallback) │
                 │  finalize transaction → atomic save      │
                 └──────────────────────────────────────────┘

chunk read  ──► ChunkMapMixin.readChunk (RETURN, post-datafix)
                 └► ChunkNbtTransformer.process(tag, policy, activeEpoch)
                      FUTURE epoch → throw (refuse world)
                      OLD + incomplete → Optional.empty() → vanilla regenerates
                      OLD + complete + supported dim → ensure blending_data
                      always → ensure seedblend epoch metadata
                 ChunkSerializerMixin.read (RETURN) → stamp epoch on ProtoChunk duck
                 LevelChunkMixin <init> → carry epoch across promotion

chunk write ──► ChunkSerializerMixin.write (RETURN)
                 assign active epoch to new chunks; persist epoch;
                 re-ensure blending_data on old completed chunks
```

## Key types

- `SeedBlendRuntimeState` — immutable record (seed, epoch, mode, blending dims),
  published once pre-level-load; the only state generation threads read.
- `SeedBlendWorldState` / `StateStore` — persisted JSON, atomic tmp→bak→move writes.
- `ChunkNbtTransformer` — pure functions over `CompoundTag`; unit-testable without a server.
- `DimensionBlendPolicy` / `DimensionPolicyFactory` — per-dimension decision + true
  vertical section bounds (`level.getMinSection()` / `getMaxSection()`).
- `SeedBlendChunkEpochAccess` — duck interface added to `ChunkAccess` by mixin.

## Why restart-required

Replacing the seed live would leave stale `RandomState`, `ChunkGeneratorStructureState`,
noise routers, random sequences, and in-flight generation tasks. The staged transaction
is applied before any of those exist and verified after they are built (see
`docs/reseed-lifecycle.md`).

## Mixin inventory

See `docs/source-audit-1.21.1.md` — six targets, all additive `@Inject`/`@Accessor`,
none touching chunk scheduling (C2ME-compatible by construction).
