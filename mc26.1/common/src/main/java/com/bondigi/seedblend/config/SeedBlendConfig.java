package com.bondigi.seedblend.config;

import com.bondigi.seedblend.SeedBlend;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code config/seedblend.json} (spec §14). Plain JSON via the Gson Minecraft bundles —
 * no configuration-library dependency. Changes affecting seed behavior require restart;
 * the file is read once at startup.
 */
public final class SeedBlendConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String FILE_NAME = "seedblend.json";

    public List<String> supportedDimensions = List.of("minecraft:overworld");
    public boolean discardIncompleteOldChunks = true;
    public boolean persistSyntheticBlendingData = true;
    public boolean requirePlanToken = true;
    public boolean warnOnUnsupportedDimensions = true;
    public boolean allowCustomNoiseGenerators = false;
    public boolean diagnosticLogging = false;

    /**
     * Loads the config, writing defaults if the file is absent. A malformed file is a
     * hard error — silently falling back to defaults could change blending policy.
     */
    public static SeedBlendConfig loadOrCreate(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            SeedBlendConfig defaults = new SeedBlendConfig();
            try {
                Files.createDirectories(configDir);
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults, writer);
                }
            } catch (IOException e) {
                SeedBlend.LOGGER.warn("[SeedBlend] Could not write default config {}: {}", file, e.toString());
            }
            return defaults;
        }
        try {
            SeedBlendConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SeedBlendConfig.class);
            if (config == null) {
                throw new JsonParseException("empty config");
            }
            return config;
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Malformed " + file + " — fix or delete it to regenerate defaults", e);
        }
    }
}
