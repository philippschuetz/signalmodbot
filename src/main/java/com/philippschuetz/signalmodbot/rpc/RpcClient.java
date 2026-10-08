package com.philippschuetz.signalmodbot.rpc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Talks newline-delimited JSON-RPC 2.0 to `signal-cli ... daemon --socket ...`.
 *
 * signal-cli pushes incoming Signal messages as JSON-RPC *notifications*
 * (method "receive", no "id") over the same connection that requests are
 * sent on, so a single persistent connection handles both directions.
 */
public class RpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RpcClient.class);
    private final Gson gson = new Gson();

    private final java.util.concurrent.ExecutorService notificationExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "signal-cli-notifications");
                t.setDaemon(true);
                return t;
            });

    private final SocketChannel channel;
    private final PrintWriter out;
    private final BufferedReader in;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
    private volatile Consumer<JsonObject> notificationHandler = n -> {};
    private final Thread readerThread;
    private volatile boolean closed = false;

    public RpcClient(Path socketPath) throws IOException {
        channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socketPath));
        OutputStream os = Channels.newOutputStream(channel);
        this.out = new PrintWriter(os, false, StandardCharsets.UTF_8);
        this.in = new BufferedReader(new java.io.InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));

        readerThread = new Thread(this::readLoop, "signal-cli-rpc-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Registers the callback invoked for every unsolicited notification (e.g. incoming messages). */
    public void onNotification(Consumer<JsonObject> handler) {
        this.notificationHandler = handler;
    }

    /** Sends a JSON-RPC request and blocks (up to timeoutSeconds) for its result. */
    public JsonElement call(String method, Object params, long timeoutSeconds) throws IOException, TimeoutException {
        String id = String.valueOf(nextId.getAndIncrement());
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("method", method);
        req.add("params", gson.toJsonTree(params));
        req.addProperty("id", id);

        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(id, future);
        String line = gson.toJson(req);
        synchronized (out) {
            out.print(line);
            out.print('\n');
            out.flush();
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("RPC call to " + method + " failed", e.getCause());
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for RPC call to " + method, e);
        }
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject msg;
                try {
                    msg = gson.fromJson(line, JsonObject.class);
                } catch (Exception e) {
                    log.warn("Failed to parse JSON-RPC line: {}", line);
                    continue;
                }
                if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
                    String id = msg.get("id").getAsString();
                    CompletableFuture<JsonElement> f = pending.remove(id);
                    if (f != null) {
                        if (msg.has("error")) {
                            f.completeExceptionally(new IOException("RPC error: " + msg.get("error")));
                        } else {
                            JsonElement result = msg.get("result");
                            f.complete(result != null ? result : com.google.gson.JsonNull.INSTANCE);
                        }
                    }
                } else if (msg.has("method")) {
                    final JsonObject notification = msg;
                    notificationExecutor.execute(() -> {
                        try {
                            notificationHandler.accept(notification);
                        } catch (Exception e) {
                            log.error("Notification handler threw", e);
                        }
                    });
                }
            }
        } catch (IOException e) {
            if (!closed) log.error("signal-cli RPC connection lost", e);
        } finally {
            pending.values().forEach(f -> f.completeExceptionally(new IOException("connection closed")));
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            notificationExecutor.shutdownNow();
            channel.close(); } catch (IOException ignored) {}
    }
}
