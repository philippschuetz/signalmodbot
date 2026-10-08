package com.philippschuetz.signalmodbot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.philippschuetz.signalmodbot.cli.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Settings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Data {
        String adminToken;
        Integer summaryIntervalMinutes;
    }

    private final Path file;
    private final Data data;

    public Settings(Path dataDir) {
        this.file = dataDir.resolve("settings.json");
        this.data = load();
        applyEnvironmentOverrides();
    }

    public String adminToken() { return data.adminToken; }
    public int summaryIntervalMinutes() { return data.summaryIntervalMinutes; }

    /** Asks for anything not saved yet. Only needs a terminal if something is missing. */
    public void ensureForRun() {
        boolean changed = false;
        if (data.adminToken == null || data.adminToken.isBlank()) {
            data.adminToken = Prompt.askSecret(
                    "Admin token (you'll DM '/auth <token>' to the bot to become admin)", false);
            changed = true;
        }
        if (data.summaryIntervalMinutes == null) {
            data.summaryIntervalMinutes = Prompt.askInt(
                    "Post a summary automatically every how many minutes? (0 = only on demand)", 60);
            changed = true;
        }
        if (changed) save();
    }

    /** `settings` mode: ask for everything again; Enter keeps the current value. */
    public void promptAll() {
        System.out.println("Press Enter to keep the current value.");
        boolean hasToken = data.adminToken != null && !data.adminToken.isBlank();
        String token = Prompt.askSecret("Admin token" + (hasToken ? " (currently set)" : ""), hasToken);
        if (!token.isEmpty()) data.adminToken = token;
        data.summaryIntervalMinutes = Prompt.askInt(
                "Post a summary automatically every how many minutes? (0 = only on demand)",
                data.summaryIntervalMinutes != null ? data.summaryIntervalMinutes : 60);
        save();
        System.out.println("Saved.");
    }

    private Data load() {
        if (!Files.exists(file)) return new Data();
        try {
            Data d = GSON.fromJson(Files.readString(file), Data.class);
            return d != null ? d : new Data();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        } catch (JsonParseException e) {
            throw new IllegalStateException("Could not parse settings in " + file, e);
        }
    }

    private void applyEnvironmentOverrides() {
        String adminToken = System.getenv("ADMIN_TOKEN");
        if (adminToken != null && !adminToken.isBlank()) {
            data.adminToken = adminToken;
        }

        String interval = System.getenv("SUMMARY_INTERVAL_MINUTES");
        if (interval != null && !interval.isBlank()) {
            try {
                data.summaryIntervalMinutes = Integer.parseInt(interval.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("SUMMARY_INTERVAL_MINUTES must be a whole number (0 or more)", e);
            }
            if (data.summaryIntervalMinutes < 0) {
                throw new IllegalStateException("SUMMARY_INTERVAL_MINUTES must be a whole number (0 or more)");
            }
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
    }
}