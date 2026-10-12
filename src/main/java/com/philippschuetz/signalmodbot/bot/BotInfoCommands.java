package com.philippschuetz.signalmodbot.bot;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public final class BotInfoCommands {

    private final String account;
    private final int summaryIntervalMinutes;

    public BotInfoCommands(String account, int summaryIntervalMinutes) {
        this.account = account;
        this.summaryIntervalMinutes = summaryIntervalMinutes;
    }

    public String info() throws IOException {
        Attributes attributes = manifestAttributes();
        var version = value(attributes, "Implementation-Version", "development");
        var commit = value(attributes, "Build-Commit", "unknown");
        var signalCliVersion = value(attributes, "Signal-Cli-Version", "unknown");
        var timeZone = environmentOrUnknown("TIME_ZONE");
        if ("unknown".equals(timeZone)) timeZone = "UTC";
        String summaries = summaryIntervalMinutes == 0
                ? "aus (nur manuell)"
                : "alle " + summaryIntervalMinutes + " Minuten";

        return """
                Bot-Informationen
                Software: signalmodbot %s (%s)
                signal-cli: %s
                Signal-Konto: %s
                Java: %s
                Laufzeit: %s
                Zeitzone: %s
                Automatische Zusammenfassungen: %s
                Quellcode: https://github.com/philippschuetz/signalmodbot
                """.formatted(version, commit, signalCliVersion, account,
                System.getProperty("java.runtime.version"), formatUptime(), timeZone, summaries).strip();
    }

    private static Attributes manifestAttributes() throws IOException {
        try (InputStream input = BotInfoCommands.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            return input == null ? new Attributes() : new Manifest(input).getMainAttributes();
        }
    }

    private static String value(Attributes attributes, String key, String fallback) {
        var value = attributes.getValue(key);
        return value == null || value.isBlank() || "unknown".equals(value) ? fallback : value;
    }

    private static String environmentOrUnknown(String key) {
        var value = System.getenv(key);
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String formatUptime() {
        var uptime = Duration.ofMillis(java.lang.management.ManagementFactory
                .getRuntimeMXBean().getUptime());
        long days = uptime.toDays();
        long hours = uptime.toHoursPart();
        long minutes = uptime.toMinutesPart();
        long seconds = uptime.toSecondsPart();
        return (days > 0 ? days + "d " : "") + hours + "h " + minutes + "m " + seconds + "s";
    }
}
