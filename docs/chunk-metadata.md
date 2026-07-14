# Chunk Metadata Format

## The `seedblend` compound

Added to the chunk root tag on save (only once a reseed state exists — a passive
install writes nothing):

```snbt
seedblend: {
    schema: 1,
    generation_epoch: 0L
}
```

- `schema` (int) — metadata format version, currently 1.
- `generation_epoch` (long) — the world epoch the chunk was generated under. Immutable.

### Interpretation on load

| Condition | Meaning | Action |
|---|---|---|
| compound missing | pre-SeedBlend chunk | treat as epoch 0 (no migration needed) |
| epoch == active | current chunk | none |
| epoch < active, status `minecraft:full` | old completed chunk | inject `blending_data` if absent (supported dims) |
| epoch < active, status below full | old incomplete chunk | discard tag → vanilla regenerates from active seed |
| epoch > active | rollback/damage | **refuse to load the world** |
| malformed compound | counted + warned | treated as missing (epoch 0), repaired on save |

## The vanilla `blending_data` compound

```snbt
blending_data: {
    min_section: -4,    // dimension's real min section (inclusive)
    max_section: 20     // dimension's real max section (exclusive)
}
```

Bounds always come from the live dimension (`LevelHeightAccessor`), never hard-coded.
Existing valid compounds — including height arrays computed by vanilla or created by a
version upgrade — are preserved verbatim; only undecodable ones are replaced (with a
warning). Chunks below `minecraft:full` never receive the compound: marking incomplete
chunks causes biome-access crashes.

## State file

`<world>/seedblend/state.json` — see `docs/reseed-lifecycle.md`. Writes are atomic
(`state.json.tmp` → rotate `state.json.bak` → move into place) and recovery prefers the
newest parseable file, failing closed if none parses.
