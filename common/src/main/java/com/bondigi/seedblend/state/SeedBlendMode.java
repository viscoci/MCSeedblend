package com.bondigi.seedblend.state;

import com.google.gson.annotations.SerializedName;

/**
 * Operating mode of the mod.
 *
 * <p>The MVP replaces the canonical world seed. A future {@code GENERATION_ONLY}
 * mode (separate seed fed only to chunk generation) must not be implemented until
 * every world-generation seed consumer has been audited (spec §5).
 */
public enum SeedBlendMode {
    @SerializedName("canonical_world_seed")
    CANONICAL_WORLD_SEED("canonical_world_seed");

    private final String id;

    SeedBlendMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
