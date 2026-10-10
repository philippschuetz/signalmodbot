package com.philippschuetz.signalmodbot.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.philippschuetz.signalmodbot.groups.DynamicMembership;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.rpc.SignalApi;
import com.philippschuetz.signalmodbot.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class GroupChatCommands {

    private static final Logger log = LoggerFactory.getLogger(GroupChatCommands.class);
    private static final String[] EMOJI_POOL = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣"};
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public record ChatRef(String id, String name) {}
    private record MemberRef(String number, String uuid) {}

    private final SignalApi api;
    private final MessageStore store;
    private final PollStore pollStore;
    private final DynamicMembership dynamicMembership;
    private final String botAccount;

    public GroupChatCommands(SignalApi api, MessageStore store, PollStore pollStore,
                             DynamicMembership dynamicMembership, String botAccount) {
        this.api = api;
        this.store = store;
        this.pollStore = pollStore;
        this.dynamicMembership = dynamicMembership;
        this.botAccount = botAccount;
    }

    public String listChats() throws Exception {
        var chats = fetchChats();
        if (chats.isEmpty()) {
            return "Der Bot wurde noch keinen Chats hinzugefügt. Nach Einladung in mindestens einen Chat, /chats erneut ausführen";
        }
        var sb = new StringBuilder("Verfügbare Chats (Nummer für weitere Befehle verwenden):");
        for (int i = 0; i < chats.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(chats.get(i).name());
        }
        return sb.toString();
    }

    public ChatRef resolveChat(String ref) throws Exception {
        if (ref.matches("-\\d+")) {
            throw new IllegalArgumentException("Chat müssen positiv sein, /chats für verfügbare Chats");
        }
        var chats = fetchChats();
        if (ref.matches("\\d{1,3}")) {
            var idx = Integer.parseInt(ref);
            if (idx < 1 || idx > chats.size()) {
                throw new IllegalArgumentException("Chat mit Nummer " + ref + " nicht gefunden, /chats für verfügbare Chats");
            }
            return chats.get(idx - 1);
        }
        for (ChatRef chat : chats) {
            if (chat.id().equals(ref)) return chat;
        }
        return new ChatRef(ref, ref);
    }

    public String startPoll(String chatRef, String[] groupNames) throws Exception {
        var chat = resolveChat(chatRef);
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

        long timestamp = api.sendToGroup(chat.id(), pollMessage(emojiToGroup));
        dynamicMembership.clear(chat.id());
        pollStore.startPoll(chat.id(), timestamp, emojiToGroup);
        return "Neue Gruppenwahl in '" + chat.name() + "' mit " + groupNames.length +
                " Gruppen. Vorherige Gruppenwahl wurde zurückgesetzt. /count für aktuellen Stand.";
    }

    public String resendPoll(String chatRef) throws Exception {
        var chat = resolveChat(chatRef);
        var poll = pollStore.activePoll(chat.id());
        if (poll == null) {
            return "Keine Gruppenwahl für '" + chat.name() + "' gespeichert. Neue Gruppenwahl mit /poll starten.";
        }

        long timestamp = api.sendToGroup(chat.id(), pollMessage(poll.emojiToGroup()));
        pollStore.startPoll(chat.id(), timestamp, poll.emojiToGroup());
        return "Gruppenwahl erneut gesendet in '" + chat.name() + "'. Vorhandene Gruppenwahlen wurden behalten.";
    }

    public String countGroups(String chatRef) throws Exception {
        var chat = resolveChat(chatRef);
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) {
            return "Keine Gruppenwahl für '" + chat.name() + "' gespeichert. Neue Gruppenwahl mit /poll starten.";
        }

        Map<String, Set<String>> byGroup = dynamicMembership.byGroup(chat.id());
        Set<String> chosenIds = new HashSet<>();
        var sb = new StringBuilder("Gruppenzählung für '" + chat.name() + "':");
        int chosen = 0;
        for (String groupName : new LinkedHashSet<>(poll.emojiToGroup().values())) {
            Set<String> members = byGroup.getOrDefault(groupName, Set.of());
            chosenIds.addAll(members);
            chosen += members.size();
            sb.append("\n• ").append(groupName).append(": ").append(members.size());
        }
        sb.append("\n\nGruppe gewählt: ").append(chosen);

        var members = fetchMemberRefs(chat.id());
        if (members == null) {
            sb.append("\n(Couldn't get the member list from Signal, so I can't say who hasn't chosen yet.)");
        } else {
            int total = 0;
            int notChosen = 0;
            for (var member : members) {
                if (isBot(member)) continue;
                total++;
                boolean hasChosen = (member.uuid() != null && chosenIds.contains(member.uuid()))
                        || (member.number() != null && chosenIds.contains(member.number()));
                if (!hasChosen) notChosen++;
            }
            sb.append("\nGruppe noch nicht gewählt: ").append(notChosen)
                    .append(" (von ").append(total).append(" Mitgliedern)");
        }
        return sb.toString();
    }

    public String describeGroups(String chatRef) throws Exception {
        ChatRef chat = resolveChat(chatRef);
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) {
            return "Keine Gruppenwahl für '" + chat.name() + "' gespeichert. Neue Gruppenwahl mit /poll starten.";
        }
        Map<String, Set<String>> byGroup = dynamicMembership.byGroup(chat.id());
        var sb = new StringBuilder("Gruppenwahl mit Name in '" + chat.name() + "':");
        for (String groupName : new LinkedHashSet<>(poll.emojiToGroup().values())) {
            Set<String> members = byGroup.getOrDefault(groupName, Set.of());
            sb.append("\n• ").append(groupName).append(": ")
                    .append(members.isEmpty() ? "(ungewählt)" : members.stream()
                            .map(memberId -> displayMemberName(chat.id(), memberId))
                            .sorted()
                            .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    public String listGroupMembers(String chatRef) throws Exception {
        var chat = resolveChat(chatRef);
        var members = fetchMemberRefs(chat.id());
        if (members == null) {
            return "Mitgliederliste für '" + chat.name() + "' nicht verfügbar, versuche /seen";
        }
        var sb = new StringBuilder("Mitglieder von '" + chat.name() + "':");
        for (var member : members) {
            if (isBot(member)) continue;
            String memberId = member.uuid() != null ? member.uuid() : member.number();
            sb.append("\n• ").append(displayMemberName(chat.id(), memberId));
        }
        return sb.toString();
    }

    public String listSeenSenders(String chatRef) throws Exception {
        var chat = resolveChat(chatRef);
        var senders = store.distinctSenders(chat.id());
        if (senders.isEmpty()) {
            return "Keine Nachrichten in '" + chat.name() + "' seit dem Beitritt des Bots, versuche /members";
        }
        var sb = new StringBuilder("Chatmitglieder, deren Nachrichten in '" + chat.name() + "' registriert wurden:");
        for (var sender : senders) {
            var memberId = sender.senderUuid() != null ? sender.senderUuid() : sender.senderNumber();
            sb.append("\n• ").append(displayMemberName(chat.id(), memberId));
        }
        return sb.toString();
    }

    private List<ChatRef> fetchChats() throws Exception {
        var groups = api.listGroups();
        List<ChatRef> chats = new ArrayList<>();
        for (JsonElement element : groups) {
            if (!element.isJsonObject()) continue;
            var group = element.getAsJsonObject();
            if (!group.has("id") || group.get("id").isJsonNull()) continue;
            if (group.has("isMember") && !group.get("isMember").getAsBoolean()) continue;
            var name = group.has("name") && !group.get("name").isJsonNull()
                    ? group.get("name").getAsString() : "(unnamed)";
            chats.add(new ChatRef(group.get("id").getAsString(), name));
        }
        chats.sort(Comparator.comparing((ChatRef chat) -> chat.name().toLowerCase()).thenComparing(ChatRef::id));
        return chats;
    }

    private String pollMessage(Map<String, String> emojiToGroup) {
        var message = new StringBuilder("Wähle deine Gruppe, indem du auf diese Nachricht reagierst:\n");
        emojiToGroup.forEach((emoji, groupName) -> message.append("\n").append(emoji).append(" ").append(groupName));
        message.append("\n\nÄndere deine Reaktion, um die Gruppe zu wechseln. Entferne deine Reaktion, um die Gruppe zu verlassen.");
        return message.toString();
    }

    private String displayMemberName(String chatId, String memberId) {
        var name = dynamicMembership.displayName(chatId, memberId);
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

    private List<MemberRef> fetchMemberRefs(String chatId) {
        try {
            var groups = api.listGroupDetail(chatId);
            if (groups.isEmpty() || !groups.get(0).isJsonObject()) return null;
            var group = groups.get(0).getAsJsonObject();
            var membersElement = group.get("members");
            if (membersElement == null || !membersElement.isJsonArray()) return null;

            List<MemberRef> members = new ArrayList<>();
            for (JsonElement element : membersElement.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    var value = element.getAsString();
                    members.add(UUID_PATTERN.matcher(value).matches()
                            ? new MemberRef(null, value) : new MemberRef(value, null));
                } else if (element.isJsonObject()) {
                    var member = element.getAsJsonObject();
                    var number = member.has("number") && !member.get("number").isJsonNull()
                            ? member.get("number").getAsString() : null;
                    var uuid = member.has("uuid") && !member.get("uuid").isJsonNull()
                            ? member.get("uuid").getAsString() : null;
                    if (number != null || uuid != null) members.add(new MemberRef(number, uuid));
                }
            }
            return members;
        } catch (Exception e) {
            log.warn("Could not fetch member list for chat {}", chatId, e);
            return null;
        }
    }

    private boolean isBot(MemberRef member) {
        return botAccount != null && (botAccount.equals(member.number()) || botAccount.equals(member.uuid()));
    }
}
