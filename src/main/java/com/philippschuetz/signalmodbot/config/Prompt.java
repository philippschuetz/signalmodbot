package com.philippschuetz.signalmodbot.config;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Prompt {

    private static final String NO_TERMINAL =
            "The bot needs a value but no terminal is attached to ask for it. " +
                    "Run this once interactively, e.g.:  docker compose run --rm signalmodbot settings";

    private static final BufferedReader STDIN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private Prompt() {}

    public static String ask(String question, String defaultValue) {
        String suffix = defaultValue != null ? " [" + defaultValue + "]" : "";
        String answer = read(question + suffix + ": ", false);
        return answer.isEmpty() && defaultValue != null ? defaultValue : answer;
    }

    public static String askSecret(String question, boolean allowEmpty) {
        while (true) {
            String answer = read(question + ": ", true);
            if (!answer.isEmpty() || allowEmpty) return answer;
            System.out.println("A value is required.");
        }
    }

    public static int askInt(String question, int defaultValue) {
        while (true) {
            String answer = ask(question, String.valueOf(defaultValue));
            try {
                int v = Integer.parseInt(answer);
                if (v >= 0) return v;
            } catch (NumberFormatException ignored) {
            }
            System.out.println("Please enter a whole number (0 or more).");
        }
    }

    private static String read(String prompt, boolean secret) {
        try {
            String value;
            Console console = System.console();
            if (console != null) {
                if (secret) {
                    char[] chars = console.readPassword("%s", prompt);
                    value = chars == null ? null : new String(chars);
                } else {
                    value = console.readLine("%s", prompt);
                }
            } else {
                System.out.print(prompt);
                System.out.flush();
                value = STDIN.readLine();
            }
            if (value == null) throw new IllegalStateException(NO_TERMINAL);
            return value.trim();
        } catch (IOException e) {
            throw new IllegalStateException(NO_TERMINAL, e);
        }
    }
}