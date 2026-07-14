# Reseed Lifecycle

## 1. Plan

`/seedblend plan <seed>` (permission 4) parses the seed (64-bit number, or text hashed
like vanilla), runs preflight checks (not already staged, differs from current seed),
prints the §5 warnings (canonical seed change, unblended Nether/End, seed-dependent
mechanics), and issues a 6-hex-digit token valid for 10 minutes against the current
epoch. Nothing is written to disk.

## 2. Commit

`/seedblend commit <token>` validates the token (single active plan, epoch-bound,
expiring), then:

- first reseed ever → creates `state.json` (fingerprint = SHA-256 of original seed +
  level name; original seed recorded),
- writes a `STAGED` transaction `{transactionId, targetSeed, targetEpoch = active+1,
  createdBy, state}` atomically.

Active world-generation state is not touched. The command replies RESTART REQUIRED.

## 3. Startup application

On the next startup, before any level exists (Fabric `SERVER_STARTING` / NeoForge
`ServerAboutToStartEvent`):

1. Load config and state; verify the world fingerprint — a state file copied to an
   unrelated world aborts startup.
2. Sanity-check the transaction (`targetEpoch == activeEpoch + 1`; level.dat seed is
   either the active seed or — crash-retry case — already the target seed).
3. Swap `PrimaryLevelData.worldOptions` to the target seed (accessor mixin). This is
   before `MinecraftServer#createLevels`, hence before `RandomState`,
   `ChunkGeneratorStructureState`, noise routers, or any generation task exists.
4. Publish the immutable runtime state (effective seed/epoch) for the chunk hooks.

## 4. Verification and finalization

After the server reports started:

1. Assert `worldData.worldGenOptions().seed() == target`.
2. Assert every `ServerLevel`'s `ChunkGeneratorStructureState.getLevelSeed() == target`
   — the deepest seed consumer we can observe.
3. On success: promote target seed/epoch to active, record previous seed, clear the
   transaction, save atomically, log the required startup summary.
4. On mismatch: throw. **No silent fallback** — either the selected seed is active or
   the server does not run.

## 5. Crash recovery (idempotence)

| Crash point | On next startup |
|---|---|
| after staging, before restart | transaction still STAGED → applies normally |
| during startup, before finalization | state file unchanged (STAGED) → the *same* transaction retries; level.dat may already carry the target seed, which step 2 explicitly allows |
| after finalization save | no transaction → normal startup at the new epoch |
| state.json torn write | `.bak` recovery; if both unreadable → fail closed |

The target epoch is fixed at commit time, so no crash sequence can increment the epoch
twice.

## 6. Cancel

`/seedblend cancel` clears a STAGED transaction before restart and saves atomically.
