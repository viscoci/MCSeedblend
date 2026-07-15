package com.bondigi.seedblend.state;

import com.bondigi.seedblend.SeedBlend;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Loads and saves {@code <world>/seedblend/state.json} with the atomic replacement
 * pattern required by spec §7: tmp → bak → atomic move, recover from bak on corruption.
 */
public final class StateStore {
    static final String DIR_NAME = "seedblend";
    static final String FILE_NAME = "state.json";
    static final String TMP_NAME = "state.json.tmp";
    static final String BAK_NAME = "state.json.bak";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private StateStore() {
    }

    public static Path stateDir(Path worldDir) {
        return worldDir.resolve(DIR_NAME);
    }

    public static Path stateFile(Path worldDir) {
        return stateDir(worldDir).resolve(FILE_NAME);
    }

    /**
     * @return the persisted state, empty when no state file exists (mod never used on
     * this world), or a recovered state from {@code state.json.bak} when the active
     * file is unreadable.
     * @throws SeedBlendStateException when a state file exists but neither the active
     * file nor the backup can be parsed — startup must fail closed.
     */
    public static Optional<SeedBlendWorldState> load(Path worldDir) {
        Path active = stateFile(worldDir);
        Path backup = stateDir(worldDir).resolve(BAK_NAME);

        boolean activeExists = Files.exists(active);
        boolean backupExists = Files.exists(backup);
        if (!activeExists && !backupExists) {
            return Optional.empty();
        }

        if (activeExists) {
            SeedBlendWorldState state = tryParse(active);
            if (state != null) {
                return Optional.of(state);
            }
            SeedBlend.LOGGER.warn("[SeedBlend] {} is unreadable, attempting recovery from {}", active, backup);
        }

        if (backupExists) {
            SeedBlendWorldState state = tryParse(backup);
            if (state != null) {
                SeedBlend.LOGGER.warn("[SeedBlend] Recovered state from backup {}", backup);
                return Optional.of(state);
            }
        }

        throw new SeedBlendStateException(
                "SeedBlend state at " + active + " is unrecoverably malformed (backup also unreadable). "
                        + "Refusing to start. Restore the file from a world backup or delete the seedblend "
                        + "directory ONLY if this world has never been reseeded.");
    }

    @Nullable
    private static SeedBlendWorldState tryParse(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            SeedBlendWorldState state = GSON.fromJson(json, SeedBlendWorldState.class);
            if (state == null || !state.isValid()) {
                return null;
            }
            return state;
        } catch (IOException | JsonParseException e) {
            SeedBlend.LOGGER.warn("[SeedBlend] Failed to read {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * Atomic save per spec §7: serialize to {@code state.json.tmp}, flush+close, move
     * current file to {@code state.json.bak}, then atomically move tmp into place.
     */
    public static void save(Path worldDir, SeedBlendWorldState state) {
        Path dir = stateDir(worldDir);
        Path active = dir.resolve(FILE_NAME);
        Path tmp = dir.resolve(TMP_NAME);
        Path bak = dir.resolve(BAK_NAME);

        try {
            Files.createDirectories(dir);

            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(state, writer);
                writer.flush();
            }

            if (Files.exists(active)) {
                Files.move(active, bak, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(tmp, active, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, active, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SeedBlendStateException("Failed to save SeedBlend state to " + active, e);
        }
    }
}
