package com.bondigi.seedblend.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir
    Path worldDir;

    private static SeedBlendWorldState sampleState() {
        SeedBlendWorldState state = new SeedBlendWorldState(
                WorldFingerprint.compute(123456789L, "world"), 123456789L);
        state.stageTransaction(new PendingTransaction(
                "4b5d3f", 9876543210L, 1L, "ServerOwner", PendingTransaction.STATE_STAGED));
        return state;
    }

    @Test
    void loadReturnsEmptyWhenNoStateFile() {
        assertTrue(StateStore.load(worldDir).isEmpty());
    }

    @Test
    void saveThenLoadRoundTrips() {
        SeedBlendWorldState original = sampleState();
        StateStore.save(worldDir, original);

        SeedBlendWorldState loaded = StateStore.load(worldDir).orElseThrow();
        assertEquals(SeedBlendWorldState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(original.worldFingerprint(), loaded.worldFingerprint());
        assertEquals(SeedBlendMode.CANONICAL_WORLD_SEED, loaded.mode());
        assertEquals(123456789L, loaded.originalSeed());
        assertEquals(0L, loaded.activeEpoch());
        assertEquals(123456789L, loaded.activeSeed());
        assertNull(loaded.previousSeed());
        assertEquals(original.pendingTransaction(), loaded.pendingTransaction());
    }

    @Test
    void transactionSurvivesReloadUnchanged_idempotence() {
        SeedBlendWorldState state = sampleState();
        StateStore.save(worldDir, state);

        // Simulate a crashed startup: state reloaded, transaction must still be STAGED.
        SeedBlendWorldState reloaded = StateStore.load(worldDir).orElseThrow();
        PendingTransaction txn = reloaded.pendingTransaction();
        assertNotNull(txn);
        assertTrue(txn.isStaged());
        assertEquals(1L, txn.targetEpoch());

        // Save again without finalizing — nothing may drift.
        StateStore.save(worldDir, reloaded);
        SeedBlendWorldState again = StateStore.load(worldDir).orElseThrow();
        assertEquals(txn, again.pendingTransaction());
        assertEquals(0L, again.activeEpoch());
    }

    @Test
    void finalizePromotesSeedAndClearsTransaction() {
        SeedBlendWorldState state = sampleState();
        state.finalizeTransaction(state.pendingTransaction());
        StateStore.save(worldDir, state);

        SeedBlendWorldState loaded = StateStore.load(worldDir).orElseThrow();
        assertEquals(1L, loaded.activeEpoch());
        assertEquals(9876543210L, loaded.activeSeed());
        assertEquals(123456789L, loaded.previousSeed());
        assertEquals(123456789L, loaded.originalSeed());
        assertNull(loaded.pendingTransaction());
        assertEquals(1L, loaded.lastSuccessfulStartupEpoch());
    }

    @Test
    void secondSaveKeepsBackupOfPreviousState() throws IOException {
        SeedBlendWorldState first = sampleState();
        StateStore.save(worldDir, first);
        SeedBlendWorldState second = sampleState();
        second.finalizeTransaction(second.pendingTransaction());
        StateStore.save(worldDir, second);

        assertTrue(Files.exists(StateStore.stateDir(worldDir).resolve("state.json.bak")));
        assertTrue(Files.exists(StateStore.stateFile(worldDir)));
        // No stray tmp file after a successful save.
        assertFalse(Files.exists(StateStore.stateDir(worldDir).resolve("state.json.tmp")));
    }

    @Test
    void corruptActiveFileRecoversFromBackup() throws IOException {
        SeedBlendWorldState first = sampleState();
        StateStore.save(worldDir, first);
        SeedBlendWorldState second = sampleState();
        second.finalizeTransaction(second.pendingTransaction());
        StateStore.save(worldDir, second); // first → .bak

        Files.writeString(StateStore.stateFile(worldDir), "{not json!!!");

        Optional<SeedBlendWorldState> recovered = StateStore.load(worldDir);
        assertTrue(recovered.isPresent());
        // Backup holds the FIRST state (pre-finalize).
        assertEquals(0L, recovered.get().activeEpoch());
        assertNotNull(recovered.get().pendingTransaction());
    }

    @Test
    void corruptActiveAndBackupFailsClosed() throws IOException {
        StateStore.save(worldDir, sampleState());
        StateStore.save(worldDir, sampleState()); // create .bak
        Files.writeString(StateStore.stateFile(worldDir), "garbage");
        Files.writeString(StateStore.stateDir(worldDir).resolve("state.json.bak"), "more garbage");

        assertThrows(SeedBlendStateException.class, () -> StateStore.load(worldDir));
    }
}
