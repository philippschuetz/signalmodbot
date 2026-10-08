package com.philippschuetz.signalmodbot.store;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MessageStore implements AutoCloseable {

    public record MessageRecord(String chatId, String senderNumber, String senderUuid,
                                 String senderName, long timestamp, String body) {}

    private final Connection conn;

    public MessageStore(Path dataDir) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException(e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("messages.db"));
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL,
                    sender_number TEXT,
                    sender_uuid TEXT,
                    sender_name TEXT,
                    ts INTEGER NOT NULL,
                    body TEXT
                )
            """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_ts ON messages(chat_id, ts)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS last_summary (
                    chat_id TEXT PRIMARY KEY,
                    ts INTEGER NOT NULL
                )
            """);
        }
    }

    public synchronized void record(MessageRecord m) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO messages (chat_id, sender_number, sender_uuid, sender_name, ts, body) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, m.chatId());
            ps.setString(2, m.senderNumber());
            ps.setString(3, m.senderUuid());
            ps.setString(4, m.senderName());
            ps.setLong(5, m.timestamp());
            ps.setString(6, m.body());
            ps.executeUpdate();
        }
    }

    public synchronized long lastSummaryTs(String chatId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT ts FROM last_summary WHERE chat_id = ?")) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public synchronized void markSummarized(String chatId, long ts) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO last_summary(chat_id, ts) VALUES (?,?) ON CONFLICT(chat_id) DO UPDATE SET ts=excluded.ts")) {
            ps.setString(1, chatId);
            ps.setLong(2, ts);
            ps.executeUpdate();
        }
    }

    public synchronized List<MessageRecord> messagesSince(String chatId, long sinceTs) throws SQLException {
        return messagesSince(chatId, sinceTs, Long.MIN_VALUE);
    }

    public synchronized List<MessageRecord> messagesSince(String chatId, long sinceTs, long notBeforeTs) throws SQLException {
        List<MessageRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender_number, sender_uuid, sender_name, ts, body FROM messages " +
                "WHERE chat_id = ? AND ts > ? AND ts >= ? ORDER BY ts")) {
            ps.setString(1, chatId);
            ps.setLong(2, sinceTs);
            ps.setLong(3, notBeforeTs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MessageRecord(chatId, rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4), rs.getString(5)));
                }
            }
        }
        return out;
    }

    /** Every distinct sender the bot has ever seen post in this chat -- handy for finding a member's number/UUID. */
    public synchronized List<MessageRecord> distinctSenders(String chatId) throws SQLException {
        List<MessageRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sender_number, sender_uuid, MAX(sender_name), MAX(ts) FROM messages " +
                "WHERE chat_id = ? GROUP BY sender_number, sender_uuid ORDER BY MAX(ts) DESC")) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MessageRecord(chatId, rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4), null));
                }
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
