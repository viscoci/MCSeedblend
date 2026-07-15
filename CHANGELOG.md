# Changelog

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
