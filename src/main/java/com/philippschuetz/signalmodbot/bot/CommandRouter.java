package com.philippschuetz.signalmodbot.bot;

import com.philippschuetz.signalmodbot.rpc.SignalApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Authenticates incoming admin commands and dispatches them to command services. */
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final AdminAuth auth;
    private final SignalApi api;
    private final GroupChatCommands groupCommands;
    private final SummaryCommands summaryCommands;
    private final BotInfoCommands botInfoCommands;

    public CommandRouter(AdminAuth auth, SignalApi api, GroupChatCommands groupCommands,
                         SummaryCommands summaryCommands, BotInfoCommands botInfoCommands) {
        this.auth = auth;
        this.api = api;
        this.groupCommands = groupCommands;
        this.summaryCommands = summaryCommands;
        this.botInfoCommands = botInfoCommands;
    }

    public void handle(String senderId, String text) {
        if (text == null || text.isBlank()) return;
        var parts = text.trim().split("\\s+");
        var command = parts[0].toLowerCase();

        try {
            if (command.equals("/auth")) {
                if (parts.length < 2) {
                    reply(senderId, "Benutzung: /auth <token>");
                    return;
                }
                boolean authenticated = auth.tryAuthenticate(senderId, parts[1]);
                reply(senderId, authenticated
                        ? "Authentifizierung erfolgreich, /help für verfügbare Befehle"
                        : "Ungültiges Token");
                return;
            }

            if (!auth.isAdmin(senderId)) {
                reply(senderId, "Nicht authentifiziert, /auth <token> zur Authentifizierung");
                return;
            }

            switch (command) {
                case "/help" -> reply(senderId, helpText());
                case "/info" -> reply(senderId, botInfoCommands.info());
                case "/deauth" -> {
                    auth.deauth(senderId);
                    reply(senderId, "Deauthentifizierung erfolgreich");
                }
                case "/chats" -> reply(senderId, groupCommands.listChats());
                case "/poll" -> {
                    requireArgs(parts, 3, "/poll <chat> <group1> <group2> ...");
                    reply(senderId, groupCommands.startPoll(parts[1], Arrays.copyOfRange(parts, 2, parts.length)));
                }
                case "/pollresend" -> {
                    requireArgs(parts, 2, "/pollresend <chat>");
                    reply(senderId, groupCommands.resendPoll(parts[1]));
                }
                case "/count" -> {
                    requireArgs(parts, 2, "/count <chat>");
                    reply(senderId, groupCommands.countGroups(parts[1]));
                }
                case "/groups" -> {
                    requireArgs(parts, 2, "/groups <chat>");
                    reply(senderId, groupCommands.describeGroups(parts[1]));
                }
                case "/members" -> {
                    requireArgs(parts, 2, "/members <chat>");
                    reply(senderId, groupCommands.listGroupMembers(parts[1]));
                }
                case "/seen" -> {
                    requireArgs(parts, 2, "/seen <chat>");
                    reply(senderId, groupCommands.listSeenSenders(parts[1]));
                }
                case "/summary" -> {
                    requireArgs(parts, 2, "/summary <chat>");
                    reply(senderId, summaryCommands.sendSummary(groupCommands.resolveChat(parts[1])));
                }
                case "/summaryall" -> reply(senderId, summaryCommands.sendAllSummaries());
                default -> reply(senderId, "Unbekannter Befehl, /help für verfügbare Befehle");
            }
        } catch (IllegalArgumentException e) {
            reply(senderId, e.getMessage());
        } catch (Exception e) {
            log.error("Command failed: {}", text, e);
            reply(senderId, "Etwas ist schiefgelaufen bei der Ausführung dieses Befehls: " + describe(e));
        }
    }

    private static void requireArgs(String[] parts, int minLength, String usage) {
        if (parts.length < minLength) throw new IllegalArgumentException("Benutzung: " + usage);
    }

    private static String describe(Exception e) {
        List<String> details = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = e; cause != null && seen.add(cause); cause = cause.getCause()) {
            var message = cause.getMessage();
            if (message == null || message.isBlank()) message = cause.getClass().getSimpleName();
            var next = cause.getCause();
            if (next == null || !message.equals(next.toString())) details.add(message);
        }
        return String.join(": ", details);
    }

    private String helpText() {
        return """
                Nach Einladung des Bots in mindestens einen Signal Chat, kann dieser per Direktnachricht gesteuert werden.
                Die folgenden Befehle sind verfügbar:
                
                /chats - Nummerierte (<chat>) Liste der verfügbaren Signal Chats
                
                /poll <chat> <group1> <group2> ... - Gruppenwahl starten (löscht vorherige Zuweisungen)
                /pollresend <chat> - letzte Gruppenwahl erneut posten (bestehende Zuweisungen bleiben)
                
                /count <chat> - Accountanzahl pro Chat und Stand der Gruppenwahl
                /groups <chat> - Gruppenwahl mit den zugewiesenen Accounts
                /members <chat> - Auflistung der Chatmitglieder
                /seen <chat> - Accounts, die seit dem Beitritt des Bots im Chat gepostet haben

                /summary <chat> - Zusammenfassung in gewählten Chat senden
                /summaryall - Zusammenfassungen in alle Chats senden

                /deauth - Aufhebung der Authentifizierung
                
                /info - Informationen über den Bot
                """;
    }

    private void reply(String recipient, String text) {
        try {
            api.sendToRecipient(recipient, text);
        } catch (Exception e) {
            log.error("Failed to reply to {}", recipient, e);
        }
    }
}
