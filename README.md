# SeedBlend

**Reseed an existing Minecraft world for future terrain — without touching a single existing chunk.**

SeedBlend is a server-side mod for Minecraft Java Edition. Stage a new seed with one command; after a restart, every chunk that already exists stays exactly as it was, and every new chunk generates from the new seed. Where new terrain meets old terrain, SeedBlend marks the old chunks as blending sources so Minecraft's **native terrain blending** (the same system that smooths pre-1.18 worlds) naturalizes elevations, cave density, and biome transitions across the boundary.

> ## ⚠️ BACK UP YOUR WORLD
> Reseeding changes the canonical world seed. **Make a complete world backup before committing a reseed.** SeedBlend refuses to guess: every reseed requires an explicit plan + commit + restart, and the plan output repeats this warning.

## Supported versions

| Minecraft | Loaders | Java | Artifact |
|---|---|---|---|
| 1.21.1 | Fabric, NeoForge | 21 | `seedblend-<loader>-1.21.1-<version>.jar` |
| 26.1.x | Fabric, NeoForge | 25 | `seedblend-<loader>-26.1-<version>.jar` (see `mc26.1/`) |
| 26.2 | Fabric, NeoForge | 25 | `seedblend-<loader>-26.2-<version>.jar` (see `mc26.2/`) |

One artifact per Minecraft version per loader — there is deliberately no universal JAR.

## Installation (server-side only)

1. Drop the jar for your loader into the server's `mods/` folder.
2. Fabric only: install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Start the server. **Installing SeedBlend alone changes nothing** — no chunk data, no seed, no metadata is written until you commit a reseed.

Clients do **not** need the mod to join. The same jar also works in single player (integrated server).

## Command workflow

All commands require permission level 4 (server console or full operator).

```
/seedblend plan 9876543210      ← preflight; prints warnings and a commit token
/seedblend commit 73A91C        ← stages the reseed (nothing changes yet)
<restart the server>            ← the staged seed applies during startup
```

Other commands:

| Command | Purpose |
|---|---|
| `/seedblend status` | Active epoch/seed, pending transaction, whether a restart is pending |
| `/seedblend cancel` | Cancels a staged (not yet applied) reseed |
| `/seedblend inspect chunk <x> <z> [dimension]` | Per-chunk epoch/blending diagnostics — never generates the chunk |
| `/seedblend verify` | Consistency checks: state file, seeds, transaction, generators, runtime counters |

Seeds may be a signed 64-bit number or any text (hashed exactly like the vanilla world-creation screen).

## How it works: generation epochs

Every reseed increments the world's **epoch**:

```
Epoch 0: original seed        Epoch 1: first reseed        Epoch 2: second reseed …
```

Each chunk permanently remembers the epoch it was generated in (`seedblend` compound in the chunk NBT; chunks from before SeedBlend count as epoch 0). On load:

- **Chunk epoch = active epoch** → normal current chunk.
- **Chunk epoch < active epoch, fully generated** → preserved bit-for-bit, and (in supported dimensions) marked with vanilla `blending_data` so adjacent new terrain blends into it.
- **Chunk epoch < active epoch, only partially generated** → discarded and regenerated from the active seed (partial old terrain would crash the blender and look broken anyway).
- **Chunk epoch > active epoch** → your state was rolled back without the world (or vice versa); SeedBlend refuses to load the world rather than corrupt it.

Epochs are immutable, classification happens as chunks load naturally — **no full-world scan, ever** — and you can reseed as many times as you like.

## What blends (and what doesn't)

Blending is enabled by default only for `minecraft:overworld` with the vanilla noise generator.

**Not blended:** the Nether, the End, superflat worlds, debug worlds, and custom chunk generators that bypass vanilla blending. The new seed still applies to future chunks in those dimensions — the border just won't be smoothed. The plan command warns about this.

Custom dimensions can opt in via `config/seedblend.json` (`supportedDimensions`) once you've verified their generator is vanilla-noise-based.

## Known limitations

Native blending naturalizes transitions; it does not merge worlds seamlessly. Expect:

- rivers and roads that don't line up across the boundary
- caves/aquifers that connect imperfectly
- structures (villages, trails, modded structures) cut off at the boundary
- visible biome transitions
- seed-dependent mechanics changing in existing chunks: slime chunks, future structure placement, anything that reads the world seed
- unblended Nether/End boundaries
- incompatibility with world generators that replace vanilla serialization or blending (a warning is logged)

## Removing the mod

Safe at any time. The world keeps its newest seed (it's in `level.dat` like any world's seed). Already-marked chunks keep blending natively. What stops: new blending marks, epoch tracking, and the commands. Re-installing resumes where you left off — state and chunk epochs stay on disk.

## Configuration

`config/seedblend.json` (created on first run):

```json
{
  "supportedDimensions": ["minecraft:overworld"],
  "discardIncompleteOldChunks": true,
  "persistSyntheticBlendingData": true,
  "requirePlanToken": true,
  "warnOnUnsupportedDimensions": true,
  "allowCustomNoiseGenerators": false,
  "diagnosticLogging": false
}
```

Seed-affecting changes require a restart.

## Reporting issues

Please include: Minecraft version, loader + version, SeedBlend version, `latest.log`, the output of `/seedblend status` and `/seedblend verify`, your `<world>/seedblend/state.json`, and whether the world has been reseeded before. For chunk-level issues add `/seedblend inspect chunk <x> <z>` for an affected chunk.

## Building from source

```
./gradlew build                # 1.21.1: fabric/build/libs + neoforge/build/libs
cd mc26.1 && ./gradlew build   # 26.1 artifacts
cd mc26.2 && ./gradlew build   # 26.2 artifacts
```

See `docs/` for architecture, chunk-metadata format, the reseed lifecycle, world-safety design, and the 1.21.1/26.1 source audits. License: MIT.
