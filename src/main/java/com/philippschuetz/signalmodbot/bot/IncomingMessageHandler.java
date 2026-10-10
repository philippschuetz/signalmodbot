package com.philippschuetz.signalmodbot.bot;

import com.philippschuetz.signalmodbot.groups.DynamicMembership;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.store.MessageStore;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

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
        var params = notification.getAsJsonObject("params");
        if (params == null) return;
        var envelope = params.getAsJsonObject("envelope");
        if (envelope == null) return;

        var dataMessage = envelope.getAsJsonObject("dataMessage");
        if (dataMessage == null) return; // typing indicators, receipts, sync messages, etc. -- ignore

        var senderNumber = firstNonNull(getString(envelope, "sourceNumber"), getString(envelope, "source"));
        var senderUuid = getString(envelope, "sourceUuid");
        var senderName = getString(envelope, "sourceName");
        long timestamp = envelope.has("timestamp") ? envelope.get("timestamp").getAsLong() : System.currentTimeMillis();

        var groupInfo = dataMessage.getAsJsonObject("groupInfo");
        var groupId = groupInfo != null ? getString(groupInfo, "groupId") : null;

        var reaction = dataMessage.getAsJsonObject("reaction");
        if (reaction != null) {
            handleReaction(groupId, senderNumber, senderUuid, senderName, reaction);
            return;
        }

        var body = getString(dataMessage, "message");
        if (body == null) return; // e.g. attachment-only messages with no text

        if (groupId != null) {
            try {
                store.record(new MessageStore.MessageRecord(groupId, senderNumber, senderUuid, senderName, timestamp, body));
            } catch (SQLException e) {
                log.error("Failed to log message for chat {}", groupId, e);
            }
        } else {
            // Direct message to the bot -- treat as an admin command attempt.
            var senderId = senderUuid != null ? senderUuid : senderNumber;
            commandRouter.handle(senderId, body);
        }
    }

    private void handleReaction(String chatId, String reactorNumber, String reactorUuid,
                                String reactorName, JsonObject reaction) {
        if (chatId == null) return; // only reactions inside a group chat matter -- this is how the poll works
        var poll = pollStore.activePoll(chatId);
        if (poll == null) return;

        long targetTs = reaction.has("targetSentTimestamp") ? reaction.get("targetSentTimestamp").getAsLong() : -1;
        if (targetTs != poll.messageTimestamp()) return; // a reaction on some other message in the chat

        var emoji = getString(reaction, "emoji");
        var groupName = emoji == null ? null : poll.emojiToGroup().get(emoji);
        if (groupName == null) return; // reacted with an emoji that isn't one of the poll's options

        var memberId = reactorUuid != null ? reactorUuid : reactorNumber;
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
