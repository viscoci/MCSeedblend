# Transition Blending

SeedBlend 0.2.0 adds **transition blending**: true old-seed → new-seed terrain
interpolation over a configurable range of chunks, working in the Overworld, the
Nether, the End, and custom noise dimensions. It supersedes what vanilla blending can
express while composing cleanly with it.

## Why vanilla blending isn't enough

Vanilla `blending_data` blending (the 1.18 upgrade system SeedBlend reuses for the
Overworld) only *height-matches*: it nudges new terrain toward sampled heights of old
chunk data, over a fixed ~7-chunk radius, and is hard-disabled outside the Overworld —
26.1 even ships a DataFixer that strips `blending_data` from Nether/End chunks.

## How transition blending works

For every generating chunk, SeedBlend scans the worldgen region (never loading chunks)
for completed old-epoch neighbors within `rangeChunks`. If any exist:

1. **Weight field.** Each terrain column gets a normalized weight
   `w = smoothstep(1 − dist/(range·16))` where `dist` is the euclidean block distance
   to the nearest old chunk. `w = 1` at the old boundary, `w = 0` at the far end of the
   range.
2. **Dual generators.** A second `RandomState` is built (and cached) from the seed of
   the *epoch the nearest old chunk was generated in* — the `seedHistory` in
   `state.json` maps every epoch to its seed, so multi-reseed boundaries blend toward
   the correct terrain.
3. **Density interpolation.** Every vanilla noise router (Overworld, Nether, caves,
   floating islands, End) wraps its final density in a `blend_density` node. SeedBlend
   hooks the chunk's `Blender` there and returns
   `w · oldSeedDensity + (1 − w) · newSeedDensity`. At `w = 1` the terrain **is** the
   old seed's terrain, so the seam at the boundary is continuous by construction; the
   landscape then morphs smoothly into the new seed across the range.
4. **Biome dithering** (optional, `blendBiomes`). Across the transition, columns pick
   the old seed's biome (sampled through the old climate sampler) with probability
   rising with `w`, dithered per-column so biome edges are ragged, not ruler-straight.

The old-seed density tree is evaluated through a plain function context whose blender
is EMPTY, so the old router's own blend node is inert — no recursion, no vanilla
interference. Old chunks are identified by their SeedBlend epoch (not `blending_data`),
which is what makes the Nether and End work: **no chunk metadata is ever injected into
those dimensions** (spec §6 still holds verbatim).

## Configuration

```json
"transition": {
  "enabled": true,
  "rangeChunks": 4,
  "dimensions": ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"],
  "blendBiomes": true
}
```

- `rangeChunks` is clamped to 1..7 — 7 is the radius vanilla guarantees to be present
  in the worldgen region (the same bound vanilla blending uses).
- Custom dimensions with `minecraft:noise`-type generators can be added to
  `dimensions`. Non-noise generators are skipped with a one-time warning.
- Vanilla `blending_data` injection stays enabled for the Overworld independently
  (`supportedDimensions`); with transition blending at `w→0` the vanilla behavior is
  the graceful floor.

## Diagnostics

- Chunks generated inside a transition zone persist `seedblend.transition_weight`
  (0-100, the chunk's max column weight); `/seedblend inspect chunk <x> <z> [dim]`
  prints it.
- `/seedblend verify` counts `transitionChunks` generated this runtime.
- `/seedblend status` shows range/biome/dimension configuration.

## Limitations

- **Carvers don't blend.** Carver caves/canyons are chunk-RNG driven, not
  density-driven; a carved tunnel may still end abruptly at the boundary (cheese and
  noodle caves DO blend — they're density-based).
- **Structures don't blend** (unchanged from 0.1.0).
- Old chunks whose epoch seed is unknown (schema-1 state files from worlds reseeded
  more than twice on 0.1.0) fall back to vanilla-only blending against those chunks.
- Feature placement (trees, ores) in transition chunks uses the new seed on
  transitional terrain — visually fine, but not "old features".
