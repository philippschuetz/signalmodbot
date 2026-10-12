package com.philippschuetz.signalmodbot.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps the signal-cli binary as a child process. We run signal-cli inside the
 * same container/process tree as the bot rather than as a separate service,
 * per the "single container" requirement -- the Java app is responsible for
 * starting it, waiting for it to become ready, and shutting it down cleanly.
 */
public class SignalCliProcess {

    private static final Logger log = LoggerFactory.getLogger(SignalCliProcess.class);
    private static final Pattern LINK_URI_PATTERN = Pattern.compile("(sgnl://linkdevice\\?[^\\s]+)");

    private final String bin;
    private final Path dataDir;

    private Process daemonProcess;

    public SignalCliProcess(String bin, Path dataDir) {
        this.bin = bin;
        this.dataDir = dataDir;
    }

    /**
     * Runs `signal-cli link -n <deviceName>` and returns the sgnl://linkdevice
     * URI it prints, without waiting for the link to be scanned. Call
     * {@link #awaitLinkCompletion(Process)} on the returned process afterwards.
     */
    public LinkAttempt startLink(String deviceName) throws IOException {
        List<String> cmd = List.of(bin, "--config", dataDir.toString(), "link", "-n", deviceName);
        log.info("Starting device link: {}", String.join(" ", cmd));
        Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();

        BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String uri = null;
        String line;
        // signal-cli prints the linking URI as the first line(s) of output, then
        // blocks until the primary device scans it and confirms.
        while ((line = stdout.readLine()) != null) {
            Matcher m = LINK_URI_PATTERN.matcher(line);
            if (m.find()) {
                uri = m.group(1);
                break;
            }
        }
        if (uri == null) {
            process.destroy();
            throw new IOException("signal-cli did not print a linking URI");
        }
        pipeRemainingOutputAsync(process);
        return new LinkAttempt(process, uri);
    }

    public record CommandResult(int exitCode, String output) {
        public boolean ok() { return exitCode == 0; }
    }

    /**
     * Starts registration of a brand-new account for a phone number:
     * `signal-cli -a ACCOUNT register [--voice] [--captcha TOKEN]`.
     * Signal then sends a verification code by SMS (or a voice call with
     * voice=true) which is passed to {@link #verify}. Note this unregisters
     * any phone currently using the same number.
     */
    public CommandResult register(String account, String captchaToken, boolean voice)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(bin, "--config", dataDir.toString(), "-a", account, "register"));
        if (voice) cmd.add("--voice");
        if (captchaToken != null) {
            cmd.add("--captcha");
            cmd.add(captchaToken); // passed as a single argv entry, so no shell quoting concerns
        }
        return runAndCapture(cmd, 60);
    }

    /** `signal-cli -a ACCOUNT verify CODE [--pin PIN]` -- completes registration. */
    public CommandResult verify(String account, String code, String pin) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(bin, "--config", dataDir.toString(), "-a", account, "verify", code));
        if (pin != null) {
            cmd.add("--pin");
            cmd.add(pin);
        }
        return runAndCapture(cmd, 60);
    }

    /** `signal-cli -a ACCOUNT updateProfile --name NAME` -- freshly registered accounts have no display name. */
    public CommandResult updateProfileName(String account, String name) throws IOException, InterruptedException {
        return runAndCapture(List.of(bin, "--config", dataDir.toString(), "-a", account,
                "updateProfile", "--name", name), 60);
    }

    private CommandResult runAndCapture(List<String> cmd, long timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.info("[signal-cli] {}", line);
                out.append(line).append('\n');
            }
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new CommandResult(-1, out + "\n(timed out)");
        }
        return new CommandResult(p.exitValue(), out.toString());
    }

    /** Blocks until the link process exits (success or failure) and returns the exit code. */
    public int awaitLinkCompletion(Process process, long timeoutSeconds) throws InterruptedException {
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            throw new InterruptedException("Timed out waiting for the device link to be confirmed");
        }
        return process.exitValue();
    }

    /**
     * Lists accounts signal-cli currently knows about in this config dir.
     * Most accounts are identified by phone number ("Number: +4912345678 ...");
     * accounts created via Signal's newer phone-numberless "Signal Login" flow
     * have no number, so this also falls back to pulling a UUID out of the
     * line if no phone number is found. That fallback is unverified against a
     * real numberless account -- if it returns nothing useful for yours, run
     * `signal-cli --config <dataDir> listAccounts` directly to see the raw
     * output and set ACCOUNT explicitly to whatever identifier it prints.
     */
    public List<String> listAccounts() throws IOException, InterruptedException {
        List<String> cmd = List.of(bin, "--config", dataDir.toString(), "listAccounts");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> accounts = new ArrayList<>();
        Pattern numberPattern = Pattern.compile("(\\+[0-9]{5,15})");
        Pattern uuidPattern = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Matcher m = numberPattern.matcher(line);
                if (m.find()) {
                    accounts.add(m.group(1));
                    continue;
                }
                Matcher u = uuidPattern.matcher(line);
                if (u.find()) {
                    log.warn("No phone number found for an account; using UUID {} instead " +
                            "(unverified for phone-numberless/Signal Login accounts -- set ACCOUNT " +
                            "explicitly if this doesn't work)", u.group(1));
                    accounts.add(u.group(1));
                }
            }
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return accounts;
    }

    /** Removes signal-cli's account data before provisioning a replacement account. */
    public void clearAccounts() throws IOException {
        Path accountsDir = dataDir.resolve("data");
        if (!Files.exists(accountsDir)) return;
        try (var paths = Files.walk(accountsDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** Starts `signal-cli -a <account> daemon --socket <path>` and returns once the socket file exists. */
    public Path startDaemon(String account, Path socketPath) throws IOException, InterruptedException {
        List<String> cmd = List.of(bin, "--config", dataDir.toString(), "-a", account,
                "daemon", "--socket", socketPath.toString());
        log.info("Starting signal-cli daemon: {}", String.join(" ", cmd));
        daemonProcess = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();
        pipeRemainingOutputAsync(daemonProcess);

        long deadline = System.currentTimeMillis() + 30_000;
        while (!java.nio.file.Files.exists(socketPath)) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("signal-cli daemon did not create its socket within 30s");
            }
            if (!daemonProcess.isAlive()) {
                throw new IOException("signal-cli daemon exited early with code " + daemonProcess.exitValue());
            }
            Thread.sleep(200);
        }
        return socketPath;
    }

    public void stopDaemon() {
        if (daemonProcess != null && daemonProcess.isAlive()) {
            daemonProcess.destroy();
        }
    }

    private void pipeRemainingOutputAsync(Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[signal-cli] {}", line);
                }
            } catch (IOException ignored) {
                // stream closed when process exits
            }
        }, "signal-cli-output");
        t.setDaemon(true);
        t.start();
    }

    public record LinkAttempt(Process process, String uri) {}
}
