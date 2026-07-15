# Changelog

## 0.2.0 — 2026-07-15

- **Transition blending**: true old-seed → new-seed terrain interpolation over a
  configurable range (`transition.rangeChunks`, 1..7, default 4), normalized smoothstep
  weighting — seamless at the boundary, pure new seed at the range edge
- **Nether and End blending** (and custom `minecraft:noise` dimensions via config):
  transition blending needs no `blending_data`, so it works where vanilla blending
  cannot; Nether/End still never receive synthetic vanilla blending metadata
- Biome dithering across the transition zone (`transition.blendBiomes`)
- Multi-reseed-aware: `seedHistory` in state.json (schema 2, auto-upgrades from 1)
  blends each boundary toward the seed of the epoch actually behind it
- Per-chunk `transition_weight` diagnostic in chunk NBT, `/seedblend inspect`, and a
  `transitionChunks` counter in `/seedblend verify`
- Fixture grew to 57 assertions including transition-weight normalization and Nether
  epoch/transition coverage

## 0.1.0 — 2026-07-14

Initial release.

- Restart-staged reseeding with plan/commit/cancel workflow and plan tokens
- Generation epochs per chunk (`seedblend` NBT compound), immutable, missing = epoch 0
- Automatic vanilla `blending_data` injection for old completed Overworld chunks on load
- Old incomplete chunks discarded and regenerated with the active seed
- Atomic `state.json` persistence with backup recovery and world fingerprint binding
- Fail-closed startup: fingerprint mismatch, future epochs, seed mismatch, malformed state
- `/seedblend status | plan | commit | cancel | inspect chunk | verify`
- `config/seedblend.json` (supported dimensions, discard/persist toggles)
- Diagnostics counters + SLF4J logging, no telemetry
- Loaders: Fabric and NeoForge for Minecraft 1.21.1 (Java 21), 26.1 (`mc26.1/`) and 26.2 (`mc26.2/`, NeoForge still beta upstream) — Java 25 for the 26.x lines
- All loader/version artifacts verified end-to-end by the RCON integration fixture (44 assertions each: plan/commit/restart/blend/multi-reseed)
