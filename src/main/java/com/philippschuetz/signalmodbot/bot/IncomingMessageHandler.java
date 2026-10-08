package com.philippschuetz.signalmodbot.bot;

import com.philippschuetz.signalmodbot.groups.DynamicMembership;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.store.MessageStore;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * signal-cli's JSON-RPC "receive" notification wraps an envelope roughly like:
 *
 * {
 *   "method": "receive",
 *   "params": {
 *     "envelope": {
 *       "sourceNumber": "+4912345678",
 *       "sourceUuid": "xxxx-...",
 *       "sourceName": "Alice",
 *       "timestamp": 1234567890,
 *       "dataMessage": {
 *         "message": "hello",
 *         "groupInfo": { "groupId": "base64==", "type": "DELIVER" },
 *         "reaction": {
 *           "emoji": "1\u20e3",
 *           "targetAuthor": "+4912345678",
 *           "targetSentTimestamp": 1234567800,
 *           "isRemove": false
 *         }
 *       }
 *     }
 *   }
 * }
 *
 * Exact field names -- especially on "reaction" -- can shift slightly between
 * signal-cli versions; this hasn't been verified against a live install. If
 * reactions aren't registering, log.debug the raw notification once and diff
 * against the field names read in handleReaction().
 */
public class IncomingMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(IncomingMessageHandler.class);

    private final MessageStore store;
    private final CommandRouter commandRouter;
    private final PollStore pollStore;
    private final DynamicMembership dynamicMembership;

    public IncomingMessageHandler(MessageStore store, CommandRouter commandRouter,
                                   PollStore pollStore, DynamicMembership dynamicMembership) {
        this.store = store;
        this.commandRouter = commandRouter;
        this.pollStore = pollStore;
        this.dynamicMembership = dynamicMembership;
    }

    public void onNotification(JsonObject notification) {
        if (!"receive".equals(getString(notification, "method"))) return;
        JsonObject params = notification.getAsJsonObject("params");
        if (params == null) return;
        JsonObject envelope = params.getAsJsonObject("envelope");
        if (envelope == null) return;

        JsonObject dataMessage = envelope.getAsJsonObject("dataMessage");
        if (dataMessage == null) return; // typing indicators, receipts, sync messages, etc. -- ignore

        String senderNumber = firstNonNull(getString(envelope, "sourceNumber"), getString(envelope, "source"));
        String senderUuid = getString(envelope, "sourceUuid");
        String senderName = getString(envelope, "sourceName");
        long timestamp = envelope.has("timestamp") ? envelope.get("timestamp").getAsLong() : System.currentTimeMillis();

        JsonObject groupInfo = dataMessage.getAsJsonObject("groupInfo");
        String groupId = groupInfo != null ? getString(groupInfo, "groupId") : null;

        JsonObject reaction = dataMessage.getAsJsonObject("reaction");
        if (reaction != null) {
            handleReaction(groupId, senderNumber, senderUuid, senderName, reaction);
            return;
        }

        String body = getString(dataMessage, "message");
        if (body == null) return; // e.g. attachment-only messages with no text

        if (groupId != null) {
            try {
                store.record(new MessageStore.MessageRecord(groupId, senderNumber, senderUuid, senderName, timestamp, body));
            } catch (SQLException e) {
                log.error("Failed to log message for chat {}", groupId, e);
            }
        } else {
            // Direct message to the bot -- treat as an admin command attempt.
            String senderId = senderUuid != null ? senderUuid : senderNumber;
            commandRouter.handle(senderId, body);
        }
    }

    private void handleReaction(String chatId, String reactorNumber, String reactorUuid,
                                String reactorName, JsonObject reaction) {
        if (chatId == null) return; // only reactions inside a group chat matter -- this is how the poll works
        PollStore.PollInfo poll = pollStore.activePoll(chatId);
        if (poll == null) return;

        long targetTs = reaction.has("targetSentTimestamp") ? reaction.get("targetSentTimestamp").getAsLong() : -1;
        if (targetTs != poll.messageTimestamp()) return; // a reaction on some other message in the chat

        String emoji = getString(reaction, "emoji");
        String groupName = emoji == null ? null : poll.emojiToGroup().get(emoji);
        if (groupName == null) return; // reacted with an emoji that isn't one of the poll's options

        String memberId = reactorUuid != null ? reactorUuid : reactorNumber;
        if (memberId == null) return;

        boolean isRemove = reaction.has("isRemove") && reaction.get("isRemove").getAsBoolean();
        if (isRemove) {
            dynamicMembership.leave(chatId, memberId, groupName);
        } else {
            dynamicMembership.join(chatId, memberId, groupName, reactorName, reactorNumber);
        }
    }

    private static String getString(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
