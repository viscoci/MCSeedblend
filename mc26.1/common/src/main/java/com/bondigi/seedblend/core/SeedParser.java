package com.bondigi.seedblend.core;

import java.util.OptionalLong;

/**
 * Parses a seed argument the same way vanilla world creation does: a value that parses
 * as a signed 64-bit integer is used directly; any other non-blank string hashes via
 * {@link String#hashCode()} (matching {@code WorldOptions.parseSeed}).
 */
public final class SeedParser {
    private SeedParser() {
    }

    public static OptionalLong parse(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
        }
        // Vanilla: textual seeds hash with String#hashCode; a zero hash falls back to
        // random in vanilla, but for reseeding we keep it deterministic and reject it.
        int hash = trimmed.hashCode();
        if (hash == 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(hash);
    }
}
