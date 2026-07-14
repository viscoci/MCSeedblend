# Compatibility

## Installation model

Server-side only. Clients never need the mod; no custom networking exists. The same jar
supports the integrated server for single player.

## Chunk pregenerators (Chunky, etc.)

Compatible, provided:

- the reseed transaction was applied (server restarted) **before** pregeneration starts,
- pregeneration uses the normal server chunk generator,
- the generator has not replaced vanilla blending.

Pregenerated new chunks get the active epoch like any other chunk.

## World-generation mods

| Category | Behavior |
|---|---|
| Vanilla-noise generators that invoke `Blender` and keep standard serialization (vanilla, most datapack dimensions) | fully compatible |
| Generators that use the seed correctly but skip vanilla blending (Terralith-style datapacks work; ground-up custom generators vary) | new terrain uses the new seed; boundaries may stay abrupt |
| Generators that replace chunk serialization, bypass the chunk pipeline, assume an immutable seed, or disable blending | unsupported — SeedBlend logs a warning instead of claiming support |

Blending injection is gated on `generator instanceof NoiseBasedChunkGenerator`; the
`allowCustomNoiseGenerators` config opts in wrapped/subclassed generators at your own
risk.

## Performance mods (C2ME, Lithium, etc.)

By construction SeedBlend avoids the conflict surface:

- hooks: world-load seed swap, chunk (de)serialization — never task schedulers,
  generation futures, or neighbor loading
- all injections are additive `@Inject`/`@Accessor`; no `@Redirect`, no `@Overwrite`
- chunk classification is a pure function of the tag being read, on the reading thread

## Dimensions

Default blending: `minecraft:overworld` only. The Nether, the End, superflat, debug
worlds, and incompatible custom dimensions never receive synthetic `blending_data`
(injecting it there produces invalid terrain, especially in the End). Custom dimensions
with verified vanilla-noise generators can be added to `supportedDimensions`.
