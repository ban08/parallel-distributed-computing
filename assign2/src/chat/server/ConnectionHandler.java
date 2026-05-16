package chat.server;

import chat.auth.PasswordHasher;
import chat.common.Frame;
import chat.session.Session;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * One TCP connection reader/router.
 *
 * C2 protocol currently supported:
 *   PING
 *   LOGIN <username> <password>
 *   TOKEN <token>     (alias: RESUME <token>)
 *   WHOAMI
 *   QUIT
 */
public final class ConnectionHandler implements Runnable {
    private static final int MAX_FRAME_CHARS = 4096;

    private final ServerState state;
    private final Socket socket;
    private Session session;

    public ConnectionHandler(ServerState state, Socket socket) {
        this.state = Objects.requireNonNull(state, "state");
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    @Override
    public void run() {
        var peer = socket.getRemoteSocketAddress();
        System.out.println("[server] connected: " + peer);

        try (Frame frame = new Frame(socket)) {
            String line;
            while ((line = frame.readLine()) != null) {
                boolean keepGoing = handleFrame(frame, line);
                if (!keepGoing) break;
            }
        } catch (IOException e) {
            // Broken connections are expected in a TCP chat server. The Session
            // is kept in SessionRegistry so the client can reconnect with TOKEN.
        } finally {
            System.out.println("[server] disconnected: " + peer);
        }
    }

    private boolean handleFrame(Frame frame, String line) throws IOException {
        try {
            if (line == null || line.isBlank()) {
                frame.writeLine("ERR empty frame");
                return true;
            }
            if (line.length() > MAX_FRAME_CHARS) {
                frame.writeLine("ERR frame too long");
                return true;
            }

            String[] parts = line.trim().split("\\s+", 3);
            String command = parts[0].toUpperCase(Locale.ROOT);

            switch (command) {
                case "PING" -> {
                    frame.writeLine("PONG");
                    return true;
                }
                case "LOGIN" -> {
                    handleLogin(frame, parts);
                    return true;
                }
                case "TOKEN", "RESUME" -> {
                    handleToken(frame, parts);
                    return true;
                }
                case "WHOAMI" -> {
                    if (session == null) frame.writeLine("ERR not authenticated");
                    else frame.writeLine("OK USER " + session.username());
                    return true;
                }
                case "QUIT" -> {
                    frame.writeLine("BYE");
                    return false;
                }
                default -> {
                    frame.writeLine("ERR unknown command");
                    return true;
                }
            }
        } catch (IllegalArgumentException e) {
            frame.writeLine("ERR bad frame");
            return true;
        }
    }

    private void handleLogin(Frame frame, String[] parts) throws IOException {
        if (parts.length != 3) {
            frame.writeLine("ERR usage LOGIN <username> <password>");
            return;
        }
        if (session != null) {
            frame.writeLine("ERR already authenticated");
            return;
        }

        String username = parts[1];
        char[] password = parts[2].toCharArray();
        try {
            Session created = state.login(username, password);
            if (created == null) {
                frame.writeLine("ERR authentication failed");
                return;
            }
            session = created;
            frame.writeLine("OK TOKEN " + created.tokenValue());
        } finally {
            PasswordHasher.clear(password);
        }
    }

    private void handleToken(Frame frame, String[] parts) throws IOException {
        if (parts.length < 2) {
            frame.writeLine("ERR usage TOKEN <token>");
            return;
        }
        if (session != null) {
            frame.writeLine("ERR already authenticated");
            return;
        }

        Session resumed = state.resume(parts[1]);
        if (resumed == null) {
            frame.writeLine("ERR invalid token");
            return;
        }
        session = resumed;
        frame.writeLine("OK RESUMED " + resumed.username());
    }
}
