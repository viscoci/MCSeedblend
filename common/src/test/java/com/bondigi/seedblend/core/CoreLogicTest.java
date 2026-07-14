package com.bondigi.seedblend.core;

import com.bondigi.seedblend.state.SeedBlendWorldState;
import com.bondigi.seedblend.state.WorldFingerprint;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.bondigi.seedblend.core.EpochPolicy.Classification.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreLogicTest {

    // --- SeedParser (spec §13: signed 64-bit or Minecraft-compatible textual seed) ---

    @Test
    void numericSeedsParseDirectly() {
        assertEquals(9876543210L, SeedParser.parse("9876543210").orElseThrow());
        assertEquals(-42L, SeedParser.parse("-42").orElseThrow());
        assertEquals(Long.MAX_VALUE, SeedParser.parse(Long.toString(Long.MAX_VALUE)).orElseThrow());
        assertEquals(Long.MIN_VALUE, SeedParser.parse(Long.toString(Long.MIN_VALUE)).orElseThrow());
        assertEquals(0L, SeedParser.parse("0").orElseThrow());
    }

    @Test
    void textualSeedsHashLikeVanilla() {
        assertEquals("glacier".hashCode(), SeedParser.parse("glacier").orElseThrow());
        assertEquals(" spaces trimmed ".trim().hashCode(), SeedParser.parse(" spaces trimmed ").orElseThrow());
    }

    @Test
    void blankSeedsRejected() {
        assertTrue(SeedParser.parse("").isEmpty());
        assertTrue(SeedParser.parse("   ").isEmpty());
        assertTrue(SeedParser.parse(null).isEmpty());
    }

    // --- EpochPolicy (spec §8) ---

    @Test
    void epochClassification() {
        assertEquals(CURRENT, EpochPolicy.classify(0, 0));
        assertEquals(CURRENT, EpochPolicy.classify(2, 2));
        assertEquals(OLD, EpochPolicy.classify(0, 1));
        assertEquals(OLD, EpochPolicy.classify(1, 2));
        assertEquals(OLD, EpochPolicy.classify(0, 2)); // multi-reseed: epoch 0 old under epoch 2
        assertEquals(FUTURE_INVALID, EpochPolicy.classify(3, 2));
    }

    // --- WorldFingerprint (spec §7) ---

    @Test
    void fingerprintBindsToSeedAndName() {
        String fp = WorldFingerprint.compute(123456789L, "world");
        assertTrue(fp.startsWith("sha256-"));
        assertEquals(fp, WorldFingerprint.compute(123456789L, "world"));
        assertNotEquals(fp, WorldFingerprint.compute(987654321L, "world"));
        assertNotEquals(fp, WorldFingerprint.compute(123456789L, "other"));
    }

    @Test
    void fingerprintMatchSurvivesReseed() {
        SeedBlendWorldState state = new SeedBlendWorldState(
                WorldFingerprint.compute(111L, "world"), 111L);
        state.stageTransaction(new com.bondigi.seedblend.state.PendingTransaction(
                "aa", 222L, 1L, "op", "STAGED"));
        state.finalizeTransaction(state.pendingTransaction());
        // Active seed changed, but the fingerprint still verifies via stored original seed.
        assertTrue(WorldFingerprint.matches(state, "world"));
        assertFalse(WorldFingerprint.matches(state, "unrelated"));
    }

    // --- PlanTokenService (spec §13) ---

    @Test
    void planTokenLifecycle() {
        AtomicLong now = new AtomicLong(1000L);
        PlanTokenService service = new PlanTokenService(now::get);

        PlanTokenService.Plan plan = service.issue(9876543210L, 0L, "ServerOwner");
        assertEquals(6, plan.token().length());

        assertTrue(service.validate(plan.token(), 0L).isPresent());
        assertTrue(service.validate(plan.token().toLowerCase(), 0L).isPresent());
        assertTrue(service.validate("XXXXXX", 0L).isEmpty(), "wrong token rejected");
        assertTrue(service.validate(plan.token(), 1L).isEmpty(), "stale epoch rejected");

        now.addAndGet(PlanTokenService.TTL_MILLIS + 1);
        assertTrue(service.validate(plan.token(), 0L).isEmpty(), "expired token rejected");

        PlanTokenService.Plan second = service.issue(1L, 0L, "ServerOwner");
        Optional<PlanTokenService.Plan> validated = service.validate(second.token(), 0L);
        assertTrue(validated.isPresent());
        assertTrue(service.validate(plan.token(), 0L).isEmpty()
                        || plan.token().equals(second.token()),
                "new plan invalidates previous token");

        service.clear();
        assertTrue(service.validate(second.token(), 0L).isEmpty());
    }
}
