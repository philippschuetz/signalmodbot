package com.philippschuetz.signalmodbot.bot;

import com.philippschuetz.signalmodbot.bot.GroupChatCommands.ChatRef;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.rpc.SignalApi;
import com.philippschuetz.signalmodbot.summary.SummaryBuilder;

/** Builds and delivers requested summaries. */
public final class SummaryCommands {

    private final SignalApi api;
    private final SummaryBuilder summaryBuilder;
    private final PollStore pollStore;

    public SummaryCommands(SignalApi api, SummaryBuilder summaryBuilder, PollStore pollStore) {
        this.api = api;
        this.summaryBuilder = summaryBuilder;
        this.pollStore = pollStore;
    }

    public String sendSummary(ChatRef chat) throws Exception {
        var summary = summaryBuilder.buildAndMark(chat.id());
        if (summary == null) {
            return "Keine Gruppen für '" + chat.name() + "' definiert, /poll zur Gruppenwahl";
        }
        api.sendToGroup(chat.id(), summary);
        return "Zusammenfassung in '" + chat.name() + "' gesendet.";
    }

    public String sendAllSummaries() throws Exception {
        for (var chatId : pollStore.activeChatIds()) {
            var summary = summaryBuilder.buildAndMark(chatId);
            if (summary != null) api.sendToGroup(chatId, summary);
        }
        return "Zusammenfassungen gesendet";
    }
}
