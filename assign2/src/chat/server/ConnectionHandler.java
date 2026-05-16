package chat.server;

import chat.auth.PasswordHasher;
import chat.common.Frame;
import chat.session.Session;

import java.io.IOException;
import java.net.Socket;
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
    private Thread writerThread;
    private long writerGeneration;
    private volatile boolean writerDrainThenStop;

    public ConnectionHandler(ServerState state, Socket socket) {
        this.state = Objects.requireNonNull(state, "state");
        this.socket = Objects.requireNonNull(socket, "socket");
    }

    @Override
    public void run() {
        var peer = socket.getRemoteSocketAddress();
        System.out.println("[server] connected: " + peer);

        Frame frame = null;
        try {
            frame = new Frame(socket);
            String line;
            while ((line = frame.readLine()) != null) {
                boolean keepGoing = handleFrame(frame, line);
                if (!keepGoing) break;
            }
        } catch (IOException e) {
            // Broken connections are expected in a TCP chat server. The Session
            // is kept in SessionRegistry so the client can reconnect with TOKEN.
        } finally {
            finishWriter();
            closeQuietly(frame);
            System.out.println("[server] disconnected: " + peer);
        }
    }

    private boolean handleFrame(Frame frame, String line) throws IOException {
        try {
            if (line == null || line.isBlank()) {
                return send(frame, "ERR empty frame");
            }
            if (line.length() > MAX_FRAME_CHARS) {
                return send(frame, "ERR frame too long");
            }

            String[] parts = line.trim().split("\\s+", 3);
            String command = parts[0].toUpperCase(Locale.ROOT);

            switch (command) {
                case "PING" -> {
                    return send(frame, "PONG");
                }
                case "LOGIN" -> {
                    return handleLogin(frame, parts);
                }
                case "TOKEN", "RESUME" -> {
                    return handleToken(frame, parts);
                }
                case "WHOAMI" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return send(frame, "OK USER " + session.username());
                }
                case "QUIT" -> {
                    send(frame, "BYE");
                    writerDrainThenStop = session != null;
                    return false;
                }
                default -> {
                    return send(frame, "ERR unknown command");
                }
            }
        } catch (IllegalArgumentException e) {
            return send(frame, "ERR bad frame");
        }
    }

    private boolean handleLogin(Frame frame, String[] parts) throws IOException {
        if (parts.length != 3) {
            return send(frame, "ERR usage LOGIN <username> <password>");
        }
        if (session != null) {
            return send(frame, "ERR already authenticated");
        }

        String username = parts[1];
        char[] password = parts[2].toCharArray();
        try {
            Session created = state.login(username, password);
            if (created == null) {
                return send(frame, "ERR authentication failed");
            }
            session = created;
            startWriter(created, frame);
            return send(frame, "OK TOKEN " + created.tokenValue());
        } finally {
            PasswordHasher.clear(password);
        }
    }

    private boolean handleToken(Frame frame, String[] parts) throws IOException {
        if (parts.length < 2) {
            return send(frame, "ERR usage TOKEN <token>");
        }
        if (session != null) {
            return send(frame, "ERR already authenticated");
        }

        Session resumed = state.resume(parts[1]);
        if (resumed == null) {
            return send(frame, "ERR invalid token");
        }
        session = resumed;
        startWriter(resumed, frame);
        return send(frame, "OK RESUMED " + resumed.username());
    }

    private boolean send(Frame frame, String line) throws IOException {
        if (writerThread == null) {
            frame.writeLine(line);
            return true;
        }
        return session != null && session.enqueue(line);
    }

    private void startWriter(Session attachedSession, Frame frame) {
        long generation = attachedSession.attachConnection();
        writerGeneration = generation;
        writerDrainThenStop = false;

        Thread writer = Thread.ofVirtual()
                .name("session-writer-" + attachedSession.username())
                .start(() -> writerLoop(attachedSession, generation, frame));
        attachedSession.attachWriter(generation, writer);
        writerThread = writer;
    }

    private void writerLoop(Session attachedSession, long generation, Frame frame) {
        try {
            while (attachedSession.ownsConnection(generation)) {
                String outbound = attachedSession.takeOutbound();
                if (!attachedSession.ownsConnection(generation)) {
                    attachedSession.enqueue(outbound);
                    return;
                }

                frame.writeLine(outbound);
                if (writerDrainThenStop && attachedSession.outboundSize() == 0) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Broken sockets are expected. The Session remains resumable.
        } finally {
            attachedSession.detachConnection(generation);
        }
    }

    private void finishWriter() {
        Thread writer = writerThread;
        if (writer == null) return;

        if (writerDrainThenStop) joinWriter(writer, 1000L);

        if (writer.isAlive()) {
            if (session != null) session.detachConnection(writerGeneration);
            writer.interrupt();
            joinWriter(writer, 1000L);
        }
    }

    private static void joinWriter(Thread writer, long millis) {
        try {
            writer.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Frame frame) {
        if (frame == null) return;
        try {
            frame.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
