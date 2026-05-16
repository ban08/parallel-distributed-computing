package chat.client;

import chat.common.Frame;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Reads server frames continuously so room messages arrive while input is idle. */
public final class ClientReader implements Runnable {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Frame frame;
    private final ClientState state;
    private final PrintStream out;
    private final PrintStream err;

    public ClientReader(Frame frame, ClientState state, PrintStream out, PrintStream err) {
        this.frame = Objects.requireNonNull(frame, "frame");
        this.state = Objects.requireNonNull(state, "state");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public void run() {
        try {
            String line;
            while (state.isRunning() && (line = frame.readLine()) != null) {
                state.observeServerFrame(line);
                out.println(formatServerFrame(line));
            }
        } catch (IOException e) {
            if (state.isRunning()) err.println("[client] connection closed: " + e.getMessage());
        } finally {
            state.stop();
        }
    }

    static String formatServerFrame(String line) {
        Objects.requireNonNull(line, "line");

        if ("PONG".equals(line)) return "[server] pong";
        if ("BYE".equals(line)) return "[server] bye";
        if (line.startsWith("ERR ")) return "[error] " + line.substring("ERR ".length());
        if (line.startsWith("OK TOKEN ")) return "[auth] logged in. token: " + line.substring("OK TOKEN ".length());
        if (line.startsWith("OK RESUMED ")) return "[auth] resumed as " + line.substring("OK RESUMED ".length());
        if (line.startsWith("OK USER ")) return "[auth] " + line.substring("OK USER ".length());
        if (line.startsWith("OK CREATED ")) return "[rooms] created " + line.substring("OK CREATED ".length());
        if (line.startsWith("JOINED ")) return "[room] joined " + line.substring("JOINED ".length());
        if (line.startsWith("LEFT ")) return "[room] left " + line.substring("LEFT ".length());
        if (line.startsWith("SYS ")) return "* " + line.substring("SYS ".length());
        if (line.startsWith("ROOMS ")) return formatRooms(line);
        if (line.startsWith("MSG ")) return formatMessage(line);
        return "< " + line;
    }

    private static String formatRooms(String line) {
        String[] parts = line.split("\\s+", 3);
        if (parts.length < 2 || "0".equals(parts[1]) || parts.length < 3 || parts[2].isBlank()) {
            return "[rooms] none";
        }
        return "[rooms] " + parts[2];
    }

    private static String formatMessage(String line) {
        String[] parts = line.split("\\s+", 4);
        if (parts.length != 4) return "< " + line;

        try {
            long epochMillis = Long.parseLong(parts[2]);
            String when = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
            return "[" + when + "] " + parts[1] + ": " + parts[3];
        } catch (NumberFormatException e) {
            return "< " + line;
        }
    }
}
