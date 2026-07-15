package com.bondigi.seedblend.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SeedHistoryTest {
    @TempDir
    Path worldDir;

    @Test
    void newStateStartsHistoryAtEpochZero() {
        SeedBlendWorldState state = new SeedBlendWorldState("sha256-x", 111L);
        assertEquals(111L, state.seedForEpoch(0).orElseThrow());
        assertTrue(state.seedForEpoch(1).isEmpty());
    }

    @Test
    void finalizeAppendsHistory() {
        SeedBlendWorldState state = new SeedBlendWorldState("sha256-x", 111L);
        state.stageTransaction(new PendingTransaction("a", 222L, 1, "op", "STAGED"));
        state.finalizeTransaction(state.pendingTransaction());
        state.stageTransaction(new PendingTransaction("b", 333L, 2, "op", "STAGED"));
        state.finalizeTransaction(state.pendingTransaction());

        assertEquals(111L, state.seedForEpoch(0).orElseThrow());
        assertEquals(222L, state.seedForEpoch(1).orElseThrow());
        assertEquals(333L, state.seedForEpoch(2).orElseThrow());
    }

    @Test
    void historyRoundTripsThroughDisk() {
        SeedBlendWorldState state = new SeedBlendWorldState("sha256-x", 111L);
        state.stageTransaction(new PendingTransaction("a", 222L, 1, "op", "STAGED"));
        state.finalizeTransaction(state.pendingTransaction());
        StateStore.save(worldDir, state);

        SeedBlendWorldState loaded = StateStore.load(worldDir).orElseThrow();
        assertEquals(SeedBlendWorldState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(111L, loaded.seedForEpoch(0).orElseThrow());
        assertEquals(222L, loaded.seedForEpoch(1).orElseThrow());
    }

    @Test
    void schemaOneFileUpgradesInPlace() throws Exception {
        // A schema-1 file (no seedHistory) as written by SeedBlend 0.1.0 after one reseed.
        String v1 = """
                {
                  "schemaVersion": 1,
                  "worldFingerprint": "sha256-abc",
                  "mode": "canonical_world_seed",
                  "originalSeed": 111,
                  "activeEpoch": 1,
                  "activeSeed": 222,
                  "previousSeed": 111,
                  "lastSuccessfulStartupEpoch": 1
                }""";
        Files.createDirectories(StateStore.stateDir(worldDir));
        Files.writeString(StateStore.stateFile(worldDir), v1);

        SeedBlendWorldState loaded = StateStore.load(worldDir).orElseThrow();
        assertEquals(111L, loaded.seedForEpoch(0).orElseThrow(), "epoch 0 from originalSeed");
        assertEquals(222L, loaded.seedForEpoch(1).orElseThrow(), "epoch 1 from activeSeed");
    }

    @Test
    void schemaOneMultiReseedGapsAreEmptyNotWrong() throws Exception {
        // Schema-1 world already at epoch 3: epochs 1 is unknowable (previousSeed only
        // covers epoch 2). The gap must read as empty, never as a guessed seed.
        String v1 = """
                {
                  "schemaVersion": 1,
                  "worldFingerprint": "sha256-abc",
                  "mode": "canonical_world_seed",
                  "originalSeed": 111,
                  "activeEpoch": 3,
                  "activeSeed": 444,
                  "previousSeed": 333,
                  "lastSuccessfulStartupEpoch": 3
                }""";
        Files.createDirectories(StateStore.stateDir(worldDir));
        Files.writeString(StateStore.stateFile(worldDir), v1);

        SeedBlendWorldState loaded = StateStore.load(worldDir).orElseThrow();
        assertEquals(111L, loaded.seedForEpoch(0).orElseThrow());
        assertTrue(loaded.seedForEpoch(1).isEmpty(), "unknowable epoch stays empty");
        assertEquals(333L, loaded.seedForEpoch(2).orElseThrow());
        assertEquals(444L, loaded.seedForEpoch(3).orElseThrow());
    }
}
