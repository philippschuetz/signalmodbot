package com.philippschuetz.signalmodbot.bot;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The admin proves themselves by DMing the bot `/auth <ADMIN_TOKEN>` once.
 * Their sender id (whichever of number/UUID signal-cli reports) is then
 * remembered as authorized. Authorization is persisted so a container
 * restart doesn't require re-authenticating; delete admins.json (or send
 * /deauth) to revoke.
 */
public class AdminAuth {

    private final String token;
    private final Path file;
    private final Set<String> authorized;
    private static final Gson GSON = new Gson();

    public AdminAuth(String token, Path dataDir) {
        this.token = token;
        this.file = dataDir.resolve("admins.json");
        this.authorized = load();
    }

    public synchronized boolean tryAuthenticate(String senderId, String suppliedToken) {
        if (senderId == null || !constantTimeEquals(token, suppliedToken)) return false;
        authorized.add(senderId);
        save();
        return true;
    }

    public synchronized boolean isAdmin(String senderId) {
        return senderId != null && authorized.contains(senderId);
    }

    public synchronized void deauth(String senderId) {
        authorized.remove(senderId);
        save();
    }

    private Set<String> load() {
        if (!Files.exists(file)) return new HashSet<>();
        try {
            String[] arr = GSON.fromJson(Files.readString(file), String[].class);
            return arr == null ? new HashSet<>() : new HashSet<>(java.util.Arrays.asList(arr));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read admin list from " + file, e);
        } catch (JsonParseException e) {
            throw new IllegalStateException("Could not parse admin list in " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(authorized));
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist admin list", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }
}
