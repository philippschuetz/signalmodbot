package com.philippschuetz.signalmodbot.summary;

import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.rpc.SignalApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SummaryScheduler.class);

    private final SummaryBuilder builder;
    private final PollStore pollStore;
    private final SignalApi api;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public SummaryScheduler(SummaryBuilder builder, PollStore pollStore, SignalApi api) {
        this.builder = builder;
        this.pollStore = pollStore;
        this.api = api;
    }

    public void start(int intervalMinutes) {
        if (intervalMinutes <= 0) {
            log.info("SUMMARY_INTERVAL_MINUTES <= 0, automatic summaries disabled");
            return;
        }
        executor.scheduleAtFixedRate(this::runOnce, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("Automatic summaries scheduled every {} minutes", intervalMinutes);
    }

    public void runOnce() {
        for (String chatId : pollStore.activeChatIds()) {
            try {
                String text = builder.buildAndMark(chatId);
                if (text != null) {
                    api.sendToGroup(chatId, text);
                }
            } catch (Exception e) {
                log.error("Failed to build/send summary for chat {}", chatId, e);
            }
        }
    }

    public void stop() {
        executor.shutdownNow();
    }
}
