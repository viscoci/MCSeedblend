package com.bondigi.seedblend.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Binds a state file to the save it belongs to (spec §7). If a state file is copied to
 * an unrelated world, startup stops with a clear error instead of silently reseeding.
 *
 * <p>Inputs are the world's <em>original</em> seed (epoch 0) and the level name — both
 * stable across the life of a save. The save path is deliberately excluded so moving or
 * renaming the server directory does not invalidate the state file.
 */
public final class WorldFingerprint {
    private WorldFingerprint() {
    }

    public static String compute(long originalSeed, String levelName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("seedblend|" + originalSeed + "|" + levelName).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256-");
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Verifies the state file belongs to this save. The stored fingerprint must match a
     * recomputation from the stored original seed and the live level name; the caller
     * additionally checks that the live level seed equals the active (or pending) seed.
     */
    public static boolean matches(SeedBlendWorldState state, String levelName) {
        return state.worldFingerprint().equals(compute(state.originalSeed(), levelName));
    }
}
