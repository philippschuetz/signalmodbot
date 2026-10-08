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
                if (parts.length < 2) { reply(senderId, "Usage: /auth <token>"); return; }
                boolean ok = auth.tryAuthenticate(senderId, parts[1]);
                reply(senderId, ok ? "Authenticated. Send /help for commands." : "Invalid token.");
                return;
            }

            if (!auth.isAdmin(senderId)) {
                reply(senderId, "Not authenticated. Send /auth <token> first.");
                return;
            }

            switch (cmd) {
                case "/help" -> reply(senderId, helpText());
                case "/deauth" -> { auth.deauth(senderId); reply(senderId, "Deauthenticated."); }
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
                        reply(senderId, "No groups defined for '" + chat.name() + "' yet -- run /poll first.");
                    } else {
                        api.sendToGroup(chat.id(), summary);
                        reply(senderId, "Summary posted in '" + chat.name() + "'.");
                    }
                }
                case "/summaryall" -> {
                    for (String chatId : pollStore.activeChatIds()) {
                        String summary = summaryBuilder.buildAndMark(chatId);
                        if (summary != null) api.sendToGroup(chatId, summary);
                    }
                    reply(senderId, "Summaries posted.");
                }
                default -> reply(senderId, "Unknown command. Send /help.");
            }
        } catch (IllegalArgumentException e) {
            reply(senderId, e.getMessage());
        } catch (Exception e) {
            log.error("Command failed: {}", text, e);
            reply(senderId, "Something went wrong handling that command: " + describe(e));
        }
    }

    private void requireArgs(String[] parts, int minLength, String usage) {
        if (parts.length < minLength) throw new IllegalArgumentException("Usage: " + usage);
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
            throw new IllegalArgumentException("Chat numbers must be positive. Send /chats to see the list.");
        }
        List<ChatRef> chats = fetchChats();
        if (ref.matches("\\d{1,3}")) {
            int idx = Integer.parseInt(ref);
            if (idx < 1 || idx > chats.size()) {
                throw new IllegalArgumentException("There's no chat number " + ref + ". Send /chats to see the list.");
            }
            return chats.get(idx - 1);
        }
        for (ChatRef c : chats) if (c.id().equals(ref)) return c;
        return new ChatRef(ref, ref); // not listed by signal-cli; pass through and let the command fail visibly if wrong
    }

    private String listChats() throws Exception {
        List<ChatRef> chats = fetchChats();
        if (chats.isEmpty()) {
            return "The bot isn't in any group chats yet. Add its Signal account to a group, then send /chats again.";
        }
        StringBuilder sb = new StringBuilder("Group chats the bot is in (use the number in other commands):");
        for (int i = 0; i < chats.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(chats.get(i).name());
        }
        sb.append("\n\nNext: /poll <number> <group1> <group2> ...");
        return sb.toString();
    }

    // ----------------------------------------------------------------- poll

    /** Starts a fresh group selection, clearing every member's previous assignment. */
    private String startPoll(ChatRef chat, String[] groupNames) throws Exception {
        if (groupNames.length < 2) {
            return "A poll needs at least two group names.";
        }
        if (groupNames.length > EMOJI_POOL.length) {
            return "Too many groups for a reaction poll (max " + EMOJI_POOL.length + ").";
        }
        Map<String, String> emojiToGroup = new LinkedHashMap<>();
        for (int i = 0; i < groupNames.length; i++) {
            emojiToGroup.put(EMOJI_POOL[i], groupNames[i]);
        }

        long ts = api.sendToGroup(chat.id(), pollMessage(emojiToGroup));
        dynamicMembership.clear(chat.id());
        pollStore.startPoll(chat.id(), ts, emojiToGroup);
        return "New poll posted in '" + chat.name() + "' with " + groupNames.length +
                " group(s). Previous group assignments were reset. " +
                "Send /count to check progress.";
    }

    /** Reposts the current choices for late joiners without clearing existing assignments. */
    private String resendPoll(ChatRef chat) throws Exception {
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) {
            return "No poll is saved for '" + chat.name() + "'. Start one with /poll first.";
        }

        long ts = api.sendToGroup(chat.id(), pollMessage(poll.emojiToGroup()));
        pollStore.startPoll(chat.id(), ts, poll.emojiToGroup());
        return "Poll resent in '" + chat.name() + "'. Existing group assignments were kept.";
    }

    private String pollMessage(Map<String, String> emojiToGroup) {
        StringBuilder sb = new StringBuilder("Pick your group by reacting to this message:\n");
        emojiToGroup.forEach((emoji, groupName) -> sb.append("\n").append(emoji).append(" ").append(groupName));
        sb.append("\n\nReact with a different one anytime to switch. Remove your reaction to leave.");
        return sb.toString();
    }

    // --------------------------------------------------------------- counts

    /**
     * Confirms how many people are in each group, plus -- if signal-cli will
     * give us the chat's member list -- how many haven't chosen yet.
     */
    private String countGroups(ChatRef chat) {
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) return "No poll has been posted in '" + chat.name() + "' yet. Run /poll first.";

        Map<String, Set<String>> byGroup = dynamicMembership.byGroup(chat.id());
        Set<String> chosenIds = new HashSet<>();
        StringBuilder sb = new StringBuilder("Group counts for '" + chat.name() + "':");
        int chosen = 0;
        for (String groupName : new LinkedHashSet<>(poll.emojiToGroup().values())) {
            Set<String> members = byGroup.getOrDefault(groupName, Set.of());
            chosenIds.addAll(members);
            chosen += members.size();
            sb.append("\n• ").append(groupName).append(": ").append(members.size());
        }
        sb.append("\n\nChosen a group: ").append(chosen);

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
            sb.append("\nHaven't chosen yet: ").append(notChosen).append(" (of ").append(total).append(" members)");
        }
        return sb.toString();
    }

    private String describeGroups(ChatRef chat) {
        PollStore.PollInfo poll = pollStore.activePoll(chat.id());
        if (poll == null) return "No poll has been posted in '" + chat.name() + "' yet. Run /poll first.";
        Map<String, Set<String>> byGroup = dynamicMembership.byGroup(chat.id());
        StringBuilder sb = new StringBuilder("Who picked what in '" + chat.name() + "':");
        for (String groupName : new LinkedHashSet<>(poll.emojiToGroup().values())) {
            Set<String> members = byGroup.getOrDefault(groupName, Set.of());
            sb.append("\n• ").append(groupName).append(": ")
              .append(members.isEmpty() ? "(nobody yet)" : members.stream()
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
        return memberId.matches("\\+[0-9]+") ? memberId : "Unknown member";
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
            return "Couldn't get the member list for '" + chat.name() + "' from Signal. Try /seen instead.";
        }
        StringBuilder sb = new StringBuilder("Members of '" + chat.name() + "':");
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
            return "Nobody has posted in '" + chat.name() + "' since the bot joined. Try /members instead.";
        }
        StringBuilder sb = new StringBuilder("People the bot has seen post in '" + chat.name() + "':");
        for (var m : senders) {
            String memberId = m.senderUuid() != null ? m.senderUuid() : m.senderNumber();
            sb.append("\n• ").append(displayMemberName(chat.id(), memberId));
        }
        return sb.toString();
    }

    private String helpText() {
        return """
                Workflow: add the bot to a group in Signal, then here in this DM:
                1. /chats — list the bot's group chats (numbered)
                2. /poll <chat> <group1> <group2> ... — start a fresh poll and reset assignments
                3. /pollresend <chat> — repost the saved poll without resetting assignments
                4. /count <chat> — confirm how many people are in each group

                <chat> is the number from /chats.

                Other commands:
                /groups <chat> — who picked what (display names)
                /members <chat> — the chat's full member list from Signal
                /seen <chat> — people the bot has seen post (display names)
                /summary <chat> — post a per-group message summary now
                /summaryall — post summaries for every chat with a poll
                /deauth — forget your admin authentication
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
