package com.philippschuetz.signalmodbot.summary;

import com.philippschuetz.signalmodbot.groups.DynamicMembership;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.store.MessageStore;

import java.sql.SQLException;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class SummaryBuilder {

    private final PollStore pollStore;
    private final DynamicMembership dynamicMembership;
    private final MessageStore store;
    private final ZoneId timeZone;

    public SummaryBuilder(PollStore pollStore, DynamicMembership dynamicMembership, MessageStore store) {
        this.pollStore = pollStore;
        this.dynamicMembership = dynamicMembership;
        this.store = store;
        String configuredTimeZone = System.getenv("TIME_ZONE");
        try {
            this.timeZone = ZoneId.of(configuredTimeZone == null || configuredTimeZone.isBlank()
                    ? "UTC" : configuredTimeZone.trim());
        } catch (DateTimeException e) {
            throw new IllegalStateException("TIME_ZONE must be a valid time-zone ID, such as Europe/Berlin or America/New_York", e);
        }
    }

    /** Builds the summary text for a chat and marks it as summarized "now". Returns null if there's nothing to say. */
    public String buildAndMark(String chatId) throws SQLException {
        PollStore.PollInfo poll = pollStore.activePoll(chatId);
        long since = store.lastSummaryTs(chatId);
        long pollTimestamp = poll == null ? 0 : poll.messageTimestamp();
        long summaryStart = Math.max(since, pollTimestamp);
        long now = System.currentTimeMillis();

        if (poll == null) {
            store.markSummarized(chatId, now);
            return null; // no groups ever defined for this chat (no /poll run) -- nothing to break down
        }

        // Group names come from the poll's options, in the order they were offered.
        Set<String> groupNames = new LinkedHashSet<>(poll.emojiToGroup().values());
        List<MessageStore.MessageRecord> messages = store.messagesSince(chatId, since, pollTimestamp);

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> wordCounts = new LinkedHashMap<>();
        for (String g : groupNames) {
            counts.put(g, 0);
            wordCounts.put(g, 0);
        }
        int unsortedCount = 0;
        int unsortedWordCount = 0;

        for (MessageStore.MessageRecord m : messages) {
            String groupName = m.senderUuid() != null ? dynamicMembership.groupFor(chatId, m.senderUuid()) : null;
            if (groupName == null && m.senderNumber() != null) groupName = dynamicMembership.groupFor(chatId, m.senderNumber());
            int words = countWords(m.body());

            if (groupName == null || !groupNames.contains(groupName)) {
                unsortedCount++;
                unsortedWordCount += words;
            } else {
                counts.merge(groupName, 1, Integer::sum);
                wordCounts.merge(groupName, words, Integer::sum);
            }
        }

        String sinceLabel = summaryStart == 0 ? "dem Anfang" : formatTime(summaryStart);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Zusammenfassung seit ").append(sinceLabel).append(":\n");
        for (String g : groupNames) {
            int c = counts.get(g);
            sb.append("\n• ").append(g).append(": ").append(c).append(" Nachricht").append(c == 1 ? "" : "s")
                    .append(", ").append(wordCounts.get(g)).append(" Wörter");
        }
        if (unsortedCount > 0) {
            sb.append("\n\n• Ohne Gruppe: ").append(unsortedCount).append(" Nachricht")
              .append(unsortedCount == 1 ? "" : "en")
              .append(", ").append(unsortedWordCount).append(" Wörter");
        }

        store.markSummarized(chatId, now);
        return sb.toString();
    }

    private int countWords(String body) {
        if (body == null || body.isBlank()) return 0;
        return body.trim().split("\\s+").length;
    }

    private String formatTime(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (VV)")
//                .withZone(timeZone)
                .format(Instant.ofEpochMilli(epochMillis));
    }
}
