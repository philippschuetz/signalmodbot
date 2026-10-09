package com.philippschuetz.signalmodbot.bot;

import com.philippschuetz.signalmodbot.groups.DynamicMembership;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.rpc.SignalApi;
import com.philippschuetz.signalmodbot.store.MessageStore;
import com.philippschuetz.signalmodbot.summary.SummaryBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * All admin interaction happens in the 1:1 chat between the admin and the
 * bot's Signal account. Intended workflow:
 *
 *   1. Add the bot's account to a Signal group (in the Signal app).
 *   2. DM the bot:  /auth <token>,  then /chats  (lists chats with numbers)
 *   3. /poll 1 TeamA TeamB   -- posts the "react to pick your group" message
 *   4. /count 1              -- confirm how many people picked each group
 *
 * Anywhere a command takes a <chat>, it accepts either the number shown by
 * /chats or the raw chat id.
 */
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);
    private static final String[] EMOJI_POOL = {"1️⃣","2️⃣","3️⃣","4️⃣","5️⃣","6️⃣","7️⃣","8️⃣","9️⃣"};
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private record ChatRef(String id, String name) {}
    private record MemberRef(String number, String uuid) {}

    private final AdminAuth auth;
    private final SummaryBuilder summaryBuilder;
    private final SignalApi api;
    private final MessageStore store;
    private final PollStore pollStore;
    private final DynamicMembership dynamicMembership;
    private final String botAccount; // the bot's own number/UUID, so it isn't counted as "hasn't chosen"

    public CommandRouter(AdminAuth auth, SummaryBuilder summaryBuilder, SignalApi api, MessageStore store,
                          PollStore pollStore, DynamicMembership dynamicMembership, String botAccount) {
        this.auth = auth;
        this.summaryBuilder = summaryBuilder;
        this.api = api;
        this.store = store;
        this.pollStore = pollStore;
        this.dynamicMembership = dynamicMembership;
        this.botAccount = botAccount;
    }

    private static String describe(Exception e) {
        List<String> details = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = e; cause != null && seen.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null || message.isBlank()) {
                message = cause.getClass().getSimpleName();
            }
            Throwable next = cause.getCause();
            if (next == null || !message.equals(next.toString())) {
                details.add(message);
            }
        }
        return String.join(": ", details);
    }

    /** senderId: signal-cli's uuid or number for whoever DM'd the bot -- used both for auth and as the reply target. */
    public void handle(String senderId, String text) {
        if (text == null || text.isBlank()) return;
        String[] parts = text.trim().split("\\s+");
        String cmd = parts[0].toLowerCase();

        try {
            if (cmd.equals("/auth")) {
                if (parts.length < 2) { reply(senderId, "Benutzung: /auth <token>"); return; }
                boolean ok = auth.tryAuthenticate(senderId, parts[1]);
                reply(senderId, ok ? "Authentifizierung erfolgreich, /help für verfügbare Befehle" : "Ungültiges Token");
                return;
            }

            if (!auth.isAdmin(senderId)) {
                reply(senderId, "Nicht authentifiziert, /auth <token> zur Authentifizierung");
                return;
            }

            switch (cmd) {
                case "/help" -> reply(senderId, helpText());
                case "/deauth" -> { auth.deauth(senderId); reply(senderId, "Deauthentifizierung erfolgreich"); }
                case "/chats" -> reply(senderId, listChats());
                case "/poll" -> {
                    requireArgs(parts, 3, "/poll <chat> <group1> <group2> ...");
                    reply(senderId, startPoll(resolveChat(parts[1]), Arrays.copyOfRange(parts, 2, parts.length)));
                }
                case "/pollresend" -> {
                    requireArgs(parts, 2, "/pollresend <chat>");
                    reply(senderId, resendPoll(resolveChat(parts[1])));
                }
                case "/count" -> {
                    requireArgs(parts, 2, "/count <chat>");
                    reply(senderId, countGroups(resolveChat(parts[1])));
                }
                case "/groups" -> {
                    requireArgs(parts, 2, "/groups <chat>");
                    reply(senderId, describeGroups(resolveChat(parts[1])));
                }
                case "/members" -> {
                    requireArgs(parts, 2, "/members <chat>");
                    reply(senderId, listGroupMembers(resolveChat(parts[1])));
                }
                case "/seen" -> {
                    requireArgs(parts, 2, "/seen <chat>");
                    reply(senderId, listSeenSenders(resolveChat(parts[1])));
                }
                case "/summary" -> {
                    requireArgs(parts, 2, "/summary <chat>");
                    ChatRef chat = resolveChat(parts[1]);
                    String summary = summaryBuilder.buildAndMark(chat.id());
                    if (summary == null) {
                        reply(senderId, "Keine Gruppen für '" + chat.name() + "' definiert, /poll zur Gruppenwahl");
                    } else {
                        api.sendToGroup(chat.id(), summary);
                        reply(senderId, "Zusammenfassung in '" + chat.name() + "' gesendet.");
                    }
                }
                case "/summaryall" -> {
                    for (String chatId : pollStore.activeChatIds()) {
                        String summary = summaryBuilder.buildAndMark(chatId);
                        if (summary != null) api.sendToGroup(chatId, summary);
                    }
                    reply(senderId, "Zusammenfassungen gesendet");
                }
                default -> reply(senderId, "Unbekannter Befehl, /help für verfügbare Befehle");
            }
        } catch (IllegalArgumentException e) {
            reply(senderId, e.getMessage());
        } catch (Exception e) {
            log.error("Command failed: {}", text, e);
            reply(senderId, "Etwas ist schiefgelaufen bei der Ausführung dieses Befehls: " + describe(e));
        }
    }

    private void requireArgs(String[] parts, int minLength, String usage) {
        if (parts.length < minLength) throw new IllegalArgumentException("Benutzung: " + usage);
    }

    // ---------------------------------------------------------------- chats

    /** Chats the bot is a member of, in a stable order (by name, then id) so /chats numbers stay put. */
    private List<ChatRef> fetchChats() throws Exception {
        JsonArray groups = api.listGroups();
        List<ChatRef> out = new ArrayList<>();
        for (JsonElement el : groups) {
            if (!el.isJsonObject()) continue;
            JsonObject group = el.getAsJsonObject();
            if (!group.has("id") || group.get("id").isJsonNull()) continue;
            if (group.has("isMember") && !group.get("isMember").getAsBoolean()) continue;
            String name = group.has("name") && !group.get("name").isJsonNull()
                    ? group.get("name").getAsString() : "(unnamed)";
            out.add(new ChatRef(group.get("id").getAsString(), name));
        }
        out.sort(Comparator.comparing((ChatRef c) -> c.name().toLowerCase()).thenComparing(ChatRef::id));
        return out;
    }

    /** Accepts the number shown by /chats, or a raw chat id. Every reply echoes the chat's name so a shifted number is obvious. */
    private ChatRef resolveChat(String ref) throws Exception {
        if (ref.matches("-\\d+")) {
            throw new IllegalArgumentException("Chat müssen positiv sein, /chats für verfügbare Chats");
        }
        List<ChatRef> chats = fetchChats();
        if (ref.matches("\\d{1,3}")) {
            int idx = Integer.parseInt(ref);
            if (idx < 1 || idx > chats.size()) {
                throw new IllegalArgumentException("Chat mit Nummer " + ref + " nicht gefunden, /chats für verfügbare Chats");
            }
            return chats.get(idx - 1);
        }
        for (ChatRef c : chats) if (c.id().equals(ref)) return c;
        return new ChatRef(ref, ref); // not listed by signal-cli; pass through and let the command fail visibly if wrong
    }

    private String listChats() throws Exception {
        List<ChatRef> chats = fetchChats();
        if (chats.isEmpty()) {
            return "Der Bot wurde noch keinen Chats hinzugefügt. Nach Einladung in mindestens einen Chat, /chats erneut ausführen";
        }
        StringBuilder sb = new StringBuilder("Verfügbare Chats (Nummer für weitere Befehle verwenden):");
        for (int i = 0; i < chats.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(chats.get(i).name());
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- poll

    /** Starts a fresh group selection, clearing every member's previous assignment. */
    private String startPoll(ChatRef chat, String[] groupNames) throws Exception {
        if (groupNames.length < 2) {
            return "Für die Gruppenwahl müssen mindestens 2 Gruppen angegeben werden, z.B. /poll <chat> GruppeA GruppeB";
        }
        if (groupNames.length > EMOJI_POOL.length) {
            return "Zu viele Gruppen für die Gruppenwahl (maximal " + EMOJI_POOL.length + ")";
        }
        Map<String, String> emojiToGroup = new LinkedHashMap<>();
        for (int i = 0; i < groupNames.length; i++) {
            emojiToGroup.put(EMOJI_POOL[i], groupNames[i]);
        }

        long ts = api.sendToGroup(chat.id(), pollMessage(emojiToGroup));
        dynamicMembership.clear(chat.id());
        pollStore.startPoll(chat.id(), ts, emojiToGroup);
        return "Neue Gruppenwahl in '" + chat.name() + "' mit " + groupNames.length +
                " Gruppen. Vorherige Gruppenwahl wurde zurückgesetzt. " +
                "/count für aktuellen Stand.";
    }

    /** Reposts the current choices for late joiners without clearing existing assignments. */
    private String resendPoll(ChatRef chat) throws Exception {
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) {
            return "Keine Gruppenwahl für '" + chat.name() + "' gespeichert. Neue Gruppenwahl mit /poll starten.";
        }

        long ts = api.sendToGroup(chat.id(), pollMessage(poll.emojiToGroup()));
        pollStore.startPoll(chat.id(), ts, poll.emojiToGroup());
        return "Gruppenwahl erneut gesendet in '" + chat.name() + "'. Vorhandene Gruppenwahlen wurden behalten.";
    }

    private String pollMessage(Map<String, String> emojiToGroup) {
        StringBuilder sb = new StringBuilder("Wähle deine Gruppe, indem du auf diese Nachricht reagierst:\n");
        emojiToGroup.forEach((emoji, groupName) -> sb.append("\n").append(emoji).append(" ").append(groupName));
        sb.append("\n\nÄndere deine Reaktion, um die Gruppe zu wechseln. Entferne deine Reaktion, um die Gruppe zu verlassen.");
        return sb.toString();
    }

    // --------------------------------------------------------------- counts

    /**
     * Confirms how many people are in each group, plus -- if signal-cli will
     * give us the chat's member list -- how many haven't chosen yet.
     */
    private String countGroups(ChatRef chat) {
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) return "Keine Gruppenwahl für '" + chat.name() + "' gespeichert. Neue Gruppenwahl mit /poll starten.";

        Map<String, Set<String>> byGroup = dynamicMembership.byGroup(chat.id());
        Set<String> chosenIds = new HashSet<>();
        StringBuilder sb = new StringBuilder("Gruppenzählung für '" + chat.name() + "':");
        int chosen = 0;
        for (String groupName : new LinkedHashSet<>(poll.emojiToGroup().values())) {
            Set<String> members = byGroup.getOrDefault(groupName, Set.of());
            chosenIds.addAll(members);
            chosen += members.size();
            sb.append("\n• ").append(groupName).append(": ").append(members.size());
        }
        sb.append("\n\nGruppe gewählt: ").append(chosen);

        List<MemberRef> members = fetchMemberRefs(chat.id());
        if (members == null) {
            sb.append("\n(Couldn't get the member list from Signal, so I can't say who hasn't chosen yet.)");
        } else {
            int total = 0;
            int notChosen = 0;
            for (MemberRef m : members) {
                if (isBot(m)) continue;
                total++;
                boolean has = (m.uuid() != null && chosenIds.contains(m.uuid()))
                        || (m.number() != null && chosenIds.contains(m.number()));
                if (!has) notChosen++;
            }
            sb.append("\nGruppe noch nicht gewählt: ").append(notChosen).append(" (von ").append(total).append(" Mitgliedern)");
        }
        return sb.toString();
    }

    private String describeGroups(ChatRef chat) {
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) return "Keine Gruppenwahl für '" + chat.name() + "' gespeichert. Neue Gruppenwahl mit /poll starten.";
        Map<String, Set<String>> byGroup = dynamicMembership.byGroup(chat.id());
        StringBuilder sb = new StringBuilder("Gruppenwahl mit Name in '" + chat.name() + "':");
        for (String groupName : new LinkedHashSet<>(poll.emojiToGroup().values())) {
            Set<String> members = byGroup.getOrDefault(groupName, Set.of());
            sb.append("\n• ").append(groupName).append(": ")
              .append(members.isEmpty() ? "(ungewählt)" : members.stream()
                      .map(memberId -> displayMemberName(chat.id(), memberId))
                      .sorted()
                      .collect(java.util.stream.Collectors.joining(", ")));
        }
        return sb.toString();
    }

    private String displayMemberName(String chatId, String memberId) {
        String name = dynamicMembership.displayName(chatId, memberId);
        if (name != null && !name.isBlank()) return name;
        try {
            for (MessageStore.MessageRecord sender : store.distinctSenders(chatId)) {
                if (memberId.equals(sender.senderUuid()) || memberId.equals(sender.senderNumber())) {
                    if (sender.senderName() != null && !sender.senderName().isBlank()) return sender.senderName();
                    if (sender.senderNumber() != null && !sender.senderNumber().isBlank()) return sender.senderNumber();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve display name for member in chat {}", chatId, e);
        }
        return memberId.matches("\\+[0-9]+") ? memberId : "Unbekanntes Mitglied";
    }

    // -------------------------------------------------------------- members

    /**
     * The chat's members as signal-cli reports them, or null if unavailable.
     * Field names in signal-cli's detailed listGroups output have varied
     * across versions, so this accepts either plain strings or objects with
     * number/uuid fields. Unverified against a live install.
     */
    private List<MemberRef> fetchMemberRefs(String chatId) {
        try {
            JsonArray groups = api.listGroupDetail(chatId);
            if (groups.isEmpty() || !groups.get(0).isJsonObject()) return null;
            JsonObject group = groups.get(0).getAsJsonObject();
            JsonElement membersEl = group.get("members");
            if (membersEl == null || !membersEl.isJsonArray()) return null;

            List<MemberRef> out = new ArrayList<>();
            for (JsonElement el : membersEl.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    String v = el.getAsString();
                    out.add(UUID_PATTERN.matcher(v).matches() ? new MemberRef(null, v) : new MemberRef(v, null));
                } else if (el.isJsonObject()) {
                    JsonObject m = el.getAsJsonObject();
                    String number = m.has("number") && !m.get("number").isJsonNull() ? m.get("number").getAsString() : null;
                    String uuid = m.has("uuid") && !m.get("uuid").isJsonNull() ? m.get("uuid").getAsString() : null;
                    if (number != null || uuid != null) out.add(new MemberRef(number, uuid));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Could not fetch member list for chat {}", chatId, e);
            return null;
        }
    }

    private boolean isBot(MemberRef m) {
        return botAccount != null && (botAccount.equals(m.number()) || botAccount.equals(m.uuid()));
    }

    private String listGroupMembers(ChatRef chat) {
        List<MemberRef> members = fetchMemberRefs(chat.id());
        if (members == null) {
            return "Mitgliederliste für '" + chat.name() + "' nicht verfügbar, versuche /seen";
        }
        StringBuilder sb = new StringBuilder("Mitglieder von '" + chat.name() + "':");
        for (MemberRef m : members) {
            if (isBot(m)) continue;
            String memberId = m.uuid() != null ? m.uuid() : m.number();
            sb.append("\n• ").append(displayMemberName(chat.id(), memberId));
        }
        return sb.toString();
    }

    private String listSeenSenders(ChatRef chat) throws Exception {
        var senders = store.distinctSenders(chat.id());
        if (senders.isEmpty()) {
            return "Keine Nachrichten in '" + chat.name() + "' seit dem Beitritt des Bots, versuche /members";
        }
        StringBuilder sb = new StringBuilder("Chatmitglieder, deren Nachrichten in '" + chat.name() + "' registriert wurden:");
        for (var m : senders) {
            String memberId = m.senderUuid() != null ? m.senderUuid() : m.senderNumber();
            sb.append("\n• ").append(displayMemberName(chat.id(), memberId));
        }
        return sb.toString();
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
