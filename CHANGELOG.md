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
- Loaders: Fabric and NeoForge for Minecraft 1.21.1 (Java 21); Minecraft 26.1 build under `mc26.1/`
