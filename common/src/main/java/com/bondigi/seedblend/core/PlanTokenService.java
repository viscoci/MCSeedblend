package com.bondigi.seedblend.core;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Issues short-lived plan tokens for the two-step {@code /seedblend plan} →
 * {@code /seedblend commit <token>} workflow (spec §13). One plan at a time; a new plan
 * invalidates the previous token; tokens expire after {@link #TTL_MILLIS}.
 */
public final class PlanTokenService {
    public static final long TTL_MILLIS = 10 * 60 * 1000L;

    public record Plan(String token, long targetSeed, long baseEpoch, String plannedBy, long issuedAtMillis) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LongSupplier clock;
    private final AtomicReference<Plan> current = new AtomicReference<>();

    public PlanTokenService(LongSupplier clockMillis) {
        this.clock = clockMillis;
    }

    public PlanTokenService() {
        this(System::currentTimeMillis);
    }

    public Plan issue(long targetSeed, long baseEpoch, String plannedBy) {
        String token = String.format(Locale.ROOT, "%06X", RANDOM.nextInt(0x1000000));
        Plan plan = new Plan(token, targetSeed, baseEpoch, plannedBy, clock.getAsLong());
        current.set(plan);
        return plan;
    }

    /**
     * @param baseEpoch the server's current active epoch; a plan issued against a
     * different epoch is stale and rejected.
     */
    public Optional<Plan> validate(String token, long baseEpoch) {
        Plan plan = current.get();
        if (plan == null
                || !plan.token().equalsIgnoreCase(token)
                || plan.baseEpoch() != baseEpoch
                || clock.getAsLong() - plan.issuedAtMillis() > TTL_MILLIS) {
            return Optional.empty();
        }
        return Optional.of(plan);
    }

    public void clear() {
        current.set(null);
    }
}
