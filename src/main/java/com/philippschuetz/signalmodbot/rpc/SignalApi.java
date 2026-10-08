package com.philippschuetz.signalmodbot.rpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Convenience wrapper around {@link RpcClient} for the handful of signal-cli
 * JSON-RPC methods this bot needs. Method/parameter names match signal-cli's
 * documented JSON-RPC API (see `signal-cli --help` / the project wiki); if a
 * newer signal-cli release renames a param, this is the only place to fix it.
 */
public class SignalApi {

    private final RpcClient rpc;

    public SignalApi(RpcClient rpc) {
        this.rpc = rpc;
    }

    /** Sends a plain text message to an individual recipient (phone number or UUID). */
    public void sendToRecipient(String recipient, String message) throws IOException, TimeoutException {
        rpc.call("send", Map.of("recipient", List.of(recipient), "message", message), 20);
    }

    /** Sends a plain text message to a group, identified by signal-cli's base64 group id. Returns the sent message's timestamp. */
    public long sendToGroup(String groupId, String message) throws IOException, TimeoutException {
        JsonElement response = rpc.call("send", Map.of("groupId", groupId, "message", message), 20);
        JsonObject result = response != null && response.isJsonObject() ? response.getAsJsonObject() : null;
        return result != null && result.has("timestamp") ? result.get("timestamp").getAsLong() : System.currentTimeMillis();
    }

    /** Lists groups the bot's account is a member of. */
    public JsonArray listGroups() throws IOException, TimeoutException {
        JsonElement result = rpc.call("listGroups", Map.of(), 20);
        if (result == null || !result.isJsonArray()) {
            throw new IOException("Unexpected listGroups response: expected a JSON array");
        }
        return result.getAsJsonArray();
    }

    /**
     * Full detail (including members) for one group -- signal-cli's listGroups
     * accepts an optional groupId filter and returns detailed member info when
     * filtered/json. Field names for member entries (number/uuid/name vs. a
     * nested address object) have varied across signal-cli versions -- if
     * CommandRouter's /members output looks wrong, log this raw result once
     * and adjust the parsing in CommandRouter accordingly.
     */
    public JsonArray listGroupDetail(String groupId) throws IOException, TimeoutException {
        JsonElement result = rpc.call("listGroups", Map.of("groupId", List.of(groupId), "detailed", true), 20);
        if (result == null || !result.isJsonArray()) {
            throw new IOException("Unexpected listGroups detail response: expected a JSON array");
        }
        return result.getAsJsonArray();
    }
}
