package com.philippschuetz.signalmodbot;

import com.philippschuetz.signalmodbot.bot.AdminAuth;
import com.philippschuetz.signalmodbot.bot.CommandRouter;
import com.philippschuetz.signalmodbot.bot.IncomingMessageHandler;
import com.philippschuetz.signalmodbot.cli.QrCode;
import com.philippschuetz.signalmodbot.cli.SignalCliProcess;
import com.philippschuetz.signalmodbot.config.Prompt;
import com.philippschuetz.signalmodbot.config.Settings;
import com.philippschuetz.signalmodbot.groups.DynamicMembership;
import com.philippschuetz.signalmodbot.groups.PollStore;
import com.philippschuetz.signalmodbot.rpc.RpcClient;
import com.philippschuetz.signalmodbot.rpc.SignalApi;
import com.philippschuetz.signalmodbot.store.MessageStore;
import com.philippschuetz.signalmodbot.summary.SummaryBuilder;
import com.philippschuetz.signalmodbot.summary.SummaryScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private static final Path DATA_DIR = Paths.get("/data"); // the Docker volume
    private static final String SIGNAL_CLI_BIN = "signal-cli"; // on the PATH inside the image

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "run";
        Files.createDirectories(DATA_DIR);
        SignalCliProcess cli = new SignalCliProcess(SIGNAL_CLI_BIN, DATA_DIR);

        try {
            Settings settings = new Settings(DATA_DIR);
            switch (mode) {
                case "link" -> runLink(cli, settings);
                case "settings" -> settings.promptAll();
                case "run" -> runBot(cli, settings);
                default -> System.out.println("Unknown mode '" + mode + "'. Use one of: link, run, settings");
            }
        } catch (IllegalStateException e) {
            log.error("Application cannot start: {}", e.getMessage(), e);
            System.err.println(e.getMessage()); // expected, user-fixable problems
            System.exit(1);
        }
    }

    private static void runLink(SignalCliProcess cli, Settings settings) throws Exception {
        List<String> existing = cli.listAccounts();
        if (!existing.isEmpty()) {
            System.out.println("Already linked to " + existing.get(0) + ". Only one account can be linked at a time.");
            System.out.println("To link a different account, delete the data volume first (this also deletes the bot's");
            System.out.println("settings and message history):  docker compose down -v");
            return;
        }

        String configuredDeviceName = System.getenv("DEVICE_NAME");
        String deviceName = configuredDeviceName == null || configuredDeviceName.isBlank()
                ? Prompt.ask("Name for this device in Signal's linked devices list", "signalmodbot")
                : configuredDeviceName.trim();
        SignalCliProcess.LinkAttempt attempt = cli.startLink(deviceName);

        System.out.println();
        System.out.println("On your phone open Signal -> Settings -> Linked Devices -> Link New Device, then scan:");
        System.out.println();
        QrCode.printToTerminal(attempt.uri());
        System.out.println();
        System.out.println("If the QR code looks garbled, enlarge the terminal window or lower the font size.");
        System.out.println("Or turn this link into a QR code yourself:");
        System.out.println(attempt.uri());
        System.out.println();
        System.out.println("Waiting for the link to be confirmed on your phone...");

        int exitCode = cli.awaitLinkCompletion(attempt.process(), 180);
        if (exitCode != 0) {
            System.out.println("Linking failed or wasn't confirmed in time (exit code " + exitCode + "). Run it again to retry.");
            return;
        }
        List<String> accounts = cli.listAccounts();
        System.out.println("Linked successfully" + (accounts.isEmpty() ? "." : " as " + accounts.get(0) + "."));

        System.out.println();
        System.out.println("A few settings the bot needs:");
        settings.ensureForRun();
        System.out.println();
        System.out.println("All set. Start the bot with:  docker compose up -d");
    }

    private static void runBot(SignalCliProcess cli, Settings settings) throws Exception {
        List<String> accounts = cli.listAccounts();
        if (accounts.isEmpty()) {
            throw new IllegalStateException("No linked Signal account found. Link one first:  docker compose run --rm signalmodbot link");
        }
        if (accounts.size() > 1) {
            throw new IllegalStateException("Found " + accounts.size() + " accounts " + accounts +
                    " but only one is supported. Delete the data volume (docker compose down -v) and link again.");
        }
        String account = accounts.get(0);
        settings.ensureForRun();

        Path socketPath = DATA_DIR.resolve("signal-cli.sock");
        Files.deleteIfExists(socketPath);
        cli.startDaemon(account, socketPath);

        try (RpcClient rpc = new RpcClient(socketPath);
             MessageStore store = new MessageStore(DATA_DIR)) {

            SignalApi api = new SignalApi(rpc);
            AdminAuth auth = new AdminAuth(settings.adminToken(), DATA_DIR);
            PollStore pollStore = new PollStore(DATA_DIR);
            DynamicMembership dynamicMembership = new DynamicMembership(DATA_DIR);
            SummaryBuilder summaryBuilder = new SummaryBuilder(pollStore, dynamicMembership, store);
            CommandRouter router = new CommandRouter(auth, summaryBuilder, api, store, pollStore, dynamicMembership, account);
            IncomingMessageHandler handler = new IncomingMessageHandler(store, router, pollStore, dynamicMembership);

            rpc.onNotification(handler::onNotification);

            SummaryScheduler scheduler = new SummaryScheduler(summaryBuilder, pollStore, api);
            scheduler.start(settings.summaryIntervalMinutes());

            log.info("signalmodbot running as {}. Waiting for messages...", account);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                scheduler.stop();
                cli.stopDaemon();
            }));

            Thread.currentThread().join();
        } finally {
            cli.stopDaemon();
        }
    }
}