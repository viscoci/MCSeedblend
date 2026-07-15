# Contributing to SeedBlend

## Repository layout

```
common/     Loader-independent logic + shared mixins (compiled per-loader, MultiLoader pattern)
fabric/     Fabric entrypoint, command/lifecycle registration (Loom)
neoforge/   NeoForge entrypoint, command/lifecycle registration (ModDevGradle)
fixture/    Headless integration fixture (scripted dedicated-server reseed cycle)
mc26.1/     Standalone Gradle build for the Minecraft 26.1 release line
mc26.2/     Standalone Gradle build for the Minecraft 26.2 release line
docs/       Architecture, source audits, safety/compatibility notes
```

Rules of the codebase (from the spec — read `OG_SPEC.md` before changing behavior):

1. **Never edit blocks in completed existing chunks.** SeedBlend only augments serialized NBT (`blending_data`, `seedblend` compounds).
2. **Chunk epochs are immutable.** Loading/saving an old chunk must not update its epoch.
3. **No full-world scans** in the normal workflow.
4. **Fail closed.** Fingerprint mismatch, future epochs, malformed state → refuse startup; never fall back silently to a different seed.
5. **No runtime dependencies** beyond the loaders themselves (no Architectury, no config libs). Mixin is fine.
6. **Thread safety:** the runtime state record is immutable and published once before levels load; chunk hooks only read it plus `LongAdder` counters.
7. Prefer loader events when timing allows; shared mixins otherwise. No `@Redirect`/`@Overwrite` — additive `@Inject` only (C2ME-friendliness).

## Building and testing

Requires JDK 21 (JDK 25 for `mc26.1/`). Gradle toolchains resolve automatically.

```
./gradlew build                 # builds fabric + neoforge 1.21.1 jars, runs unit tests
./gradlew :common:test          # unit tests only
cd mc26.1 && ./gradlew build    # Minecraft 26.1 artifacts + tests (Java 25)

# Integration fixture (~5 min per run, boots 4 dedicated servers via RCON):
powershell -File fixture/reseed-fixture.ps1                      # 1.21.1 Fabric
powershell -File fixture/reseed-fixture.ps1 -Loader neoforge     # 1.21.1 NeoForge
powershell -File fixture/reseed-fixture.ps1 -ProjectSubdir mc26.1 -JavaHome <jdk25>            # 26.1 Fabric
powershell -File fixture/reseed-fixture.ps1 -Loader neoforge -ProjectSubdir mc26.1 -JavaHome <jdk25>  # 26.1 NeoForge
powershell -File fixture/reseed-fixture.ps1 -ProjectSubdir mc26.2 -JavaHome <jdk25>            # 26.2 Fabric
powershell -File fixture/reseed-fixture.ps1 -Loader neoforge -ProjectSubdir mc26.2 -JavaHome <jdk25>  # 26.2 NeoForge
```

The integration fixture must pass on both loaders before a release: it drives plan →
commit → restart → blending/epoch verification → second reseed (44 assertions) on a
real dedicated server.

## Version support

One artifact per Minecraft version per loader. Do not try to make a jar span Minecraft versions. Ports to new Minecraft versions get their own source audit in `docs/` answering the 15 questions in spec §26 before any mixin is written.

## Commit style

Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`). Subject ≤ 72 chars, body explains the why when non-obvious.
