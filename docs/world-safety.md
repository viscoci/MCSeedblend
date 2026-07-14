# World Safety Design

## Prime directive

SeedBlend never regenerates, reshapes, or edits blocks in completed existing chunks.
Its writes are limited to: the `seedblend` NBT compound, the vanilla `blending_data`
compound (absent → synthesized; valid → untouched), `seedblend/state.json`, and the
canonical seed in `level.dat` (via the normal vanilla save path after an explicit,
committed, restarted reseed).

## Fail-closed conditions (startup refused)

- world fingerprint mismatch (state file copied to an unrelated world)
- a chunk with an epoch newer than the active world epoch (state rolled back without
  the world, or vice versa)
- state file unrecoverably malformed (active and backup both unreadable)
- pending seed cannot be applied before generator initialization (world data is not
  `PrimaryLevelData`)
- post-start verification finds any level not using the expected seed

There is no silent fallback: after reporting a reseed as applied, the selected seed is
active or the server refuses to run.

## Backups

- A complete world backup is recommended in the plan output, the commit reply, and the
  README. The MVP does not create full automatic backups (saves may be huge).
- Small safety copies are kept automatically: `state.json.bak` rotates on every state
  write.

## Incomplete old chunks

Chunks below `minecraft:full` from an older epoch are treated as absent and regenerated
from the active seed. Injecting blending metadata into them is known to cause
biome-access crashes; preserving half-generated old-seed terrain would corrupt blending
inputs. Pre-full chunks have no entities (separate `entities/` region files are written
at full) and no POI records, so discarding the tag leaves nothing stale behind.

## Behavioral blast radius of a canonical reseed (documented, warned)

Slime chunks; future structure placement everywhere (including Nether/End, which are
not blended); RNG of features in not-yet-completed chunks; seed-reading mods and
commands. See spec §5 — the plan command prints this warning verbatim before a token is
issued.

## Removal behavior

Uninstalling leaves a fully valid vanilla world on the newest seed. Injected
`blending_data` remains valid vanilla data; `seedblend` compounds are inert unknown NBT
that vanilla preserves. Reinstalling resumes epoch tracking from the on-disk state.
