package com.philippschuetz.signalmodbot.groups;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which group each person is in, per chat, chosen by reacting to a poll
 * message (see PollStore) with the emoji for that group. One group per
 * member per chat: reacting with a different emoji switches it; removing
 * the reaction leaves it. This is the only source of group membership --
 * there is no separate static config.
 */
public class DynamicMembership {

    private static final class Assignment {
        String groupName;
        String displayName;
        String number;

        Assignment(String groupName, String displayName, String number) {
            this.groupName = groupName;
            this.displayName = displayName;
            this.number = number;
        }
    }

    // chatId -> memberId (number or uuid) -> assignment
    private final Map<String, Map<String, Assignment>> data = new ConcurrentHashMap<>();
    private final Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public DynamicMembership(Path dataDir) {
        this.file = dataDir.resolve("dynamic_membership.json");
        load();
    }

    public synchronized void join(String chatId, String memberId, String groupName,
                                  String displayName, String number) {
        data.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>())
                .put(memberId, new Assignment(groupName, displayName, number));
        save();
    }

    public synchronized void clear(String chatId) {
        data.remove(chatId);
        save();
    }

    /** Only clears the mapping if it still points at groupName (a stale removal after a switch is a no-op). */
    public synchronized void leave(String chatId, String memberId, String groupName) {
        Map<String, Assignment> chat = data.get(chatId);
        Assignment assignment = chat == null ? null : chat.get(memberId);
        if (assignment != null && groupName.equals(assignment.groupName)) {
            chat.remove(memberId);
            save();
        }
    }

    public String groupFor(String chatId, String memberId) {
        Map<String, Assignment> chat = data.get(chatId);
        Assignment assignment = chat == null ? null : chat.get(memberId);
        return assignment == null ? null : assignment.groupName;
    }

    public String displayName(String chatId, String memberId) {
        Map<String, Assignment> chat = data.get(chatId);
        Assignment assignment = chat == null ? null : chat.get(memberId);
        if (assignment == null) return null;
        if (assignment.displayName != null && !assignment.displayName.isBlank()) return assignment.displayName;
        return assignment.number;
    }

    /** All current members, grouped by their chosen group name, for this chat. */
    public synchronized Map<String, Set<String>> byGroup(String chatId) {
        Map<String, Set<String>> result = new TreeMap<>();
        Map<String, Assignment> chat = data.get(chatId);
        if (chat == null) return result;
        chat.forEach((memberId, assignment) ->
                result.computeIfAbsent(assignment.groupName, k -> new TreeSet<>()).add(memberId));
        return result;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file));
            if (!root.isJsonObject()) throw new JsonParseException("Expected an object");
            root.getAsJsonObject().entrySet().forEach(chatEntry -> {
                if (!chatEntry.getValue().isJsonObject()) {
                    throw new JsonParseException("Expected member map for chat " + chatEntry.getKey());
                }
                Map<String, Assignment> members = new ConcurrentHashMap<>();
                for (Map.Entry<String, JsonElement> memberEntry : chatEntry.getValue().getAsJsonObject().entrySet()) {
                    JsonElement value = memberEntry.getValue();
                    Assignment assignment;
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        assignment = new Assignment(value.getAsString(), null, null);
                    } else if (value.isJsonObject()) {
                        JsonObject object = value.getAsJsonObject();
                        assignment = new Assignment(
                                object.has("groupName") ? object.get("groupName").getAsString() : null,
                                object.has("displayName") && !object.get("displayName").isJsonNull()
                                        ? object.get("displayName").getAsString() : null,
                                object.has("number") && !object.get("number").isJsonNull()
                                        ? object.get("number").getAsString() : null);
                        if (assignment.groupName == null) {
                            throw new JsonParseException("Missing groupName for member " + memberEntry.getKey());
                        }
                    } else {
                        throw new JsonParseException("Invalid assignment for member " + memberEntry.getKey());
                    }
                    members.put(memberEntry.getKey(), assignment);
                }
                data.put(chatEntry.getKey(), members);
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not read dynamic membership from " + file, e);
        } catch (JsonParseException e) {
            throw new IllegalStateException("Could not parse dynamic membership in " + file, e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist dynamic membership", e);
        }
    }
}
