package com.philippschuetz.signalmodbot.groups;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the currently-active "react to pick your group" poll message per chat. */
public class PollStore {

    public record PollInfo(long messageTimestamp, Map<String, String> emojiToGroup) {}

    private final Map<String, PollInfo> polls = new ConcurrentHashMap<>();
    private final Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public PollStore(Path dataDir) {
        this.file = dataDir.resolve("polls.json");
        load();
    }

    public synchronized void startPoll(String chatId, long messageTimestamp, Map<String, String> emojiToGroup) {
        polls.put(chatId, new PollInfo(messageTimestamp, new LinkedHashMap<>(emojiToGroup)));
        save();
    }

    public PollInfo activePoll(String chatId) {
        return polls.get(chatId);
    }

    /** Every chat that currently has an active poll -- i.e. every chat with groups defined at all. */
    public Set<String> activeChatIds() {
        return polls.keySet();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, PollInfo> loaded = GSON.fromJson(Files.readString(file),
                    new TypeToken<Map<String, PollInfo>>() {}.getType());
            if (loaded != null) polls.putAll(loaded);
        } catch (IOException ignored) {
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(polls));
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist poll state", e);
        }
    }
}
