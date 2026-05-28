package chat.server;

import chat.ai.OllamaClient;
import chat.auth.PasswordHasher;
import chat.auth.User;
import chat.common.Frame;
import chat.room.Room;
import chat.room.RoomMessage;
import chat.session.Session;

import java.io.IOException;
import java.net.Socket;
import java.util.Locale;
import java.util.Objects;

/**
 * One TCP connection reader/router.
 *
 * Server-side dispatcher in the sense of 2rpc.pdf slide 13: handleFrame parses
 * the (service, procedure) pair out of the incoming text frame and forwards
 * to a per-procedure "server stub" method (handleLogin, handleJoin, ...) that
 * unmarshals arguments, invokes the local function, and marshals the reply.
 * The service is implicit (chat); the procedure is the first whitespace token.
 *
 * Only request/response commands follow the RPC pattern. Unsolicited
 * server-to-client traffic (room broadcasts, SYS messages, HIST replay) is
 * delivered through the per-session writer loop driven by Room.broadcast and
 * is intentionally not RPC.
 *
 * C2 protocol currently supported:
 *   PING
 *   REGISTER <username> <password>
 *   LOGIN <username> <password>
 *   TOKEN <token>     (alias: RESUME <token>)
 *   WHOAMI
 *   LIST
 *   CREATE <roomName>
 *   CREATE <roomName> AI <prompt>
 *   CREATE_AI <roomName> -- <prompt>
 *   JOIN <roomName>
 *   MSG <text>
 *   LEAVE
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
                case "REGISTER" -> {
                    return handleRegister(frame, parts);
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
                case "LIST" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return handleList(frame);
                }
                case "CREATE" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return handleCreate(frame, tail(parts));
                }
                case "CREATE_AI" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return handleCreateAI(frame, tail(parts));
                }
                case "JOIN" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return handleJoin(frame, tail(parts));
                }
                case "MSG" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return handleMessage(frame, tail(parts));
                }
                case "LEAVE" -> {
                    if (session == null) return send(frame, "ERR not authenticated");
                    return handleLeave(frame);
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

    private boolean handleList(Frame frame) throws IOException {
        return send(frame, roomListLine());
    }

    private String roomListLine() {
        var names = state.rooms().names();
        String response = "ROOMS " + names.size();
        if (!names.isEmpty()) response += " " + String.join(" ", names);
        return response;
    }

    private boolean handleCreate(Frame frame, String tail) throws IOException {
        if (tail == null || tail.isBlank()) {
            return send(frame, "ERR usage CREATE <roomName> [AI <prompt>]");
        }

        AiCreate aiCreate = parseAiCreate(tail, true, false);
        if (aiCreate != null) {
            return createAiRoom(frame, aiCreate, "ERR usage CREATE <roomName> AI <prompt>");
        }

        try {
            Room room = state.rooms().create(tail);
            return send(frame, "OK CREATED " + room.name());
        } catch (IllegalArgumentException e) {
            if (state.rooms().exists(tail)) return send(frame, "ERR room exists");
            return send(frame, "ERR bad room name");
        }
    }

    private boolean handleCreateAI(Frame frame, String tail) throws IOException {
        AiCreate aiCreate = parseAiCreate(tail, false, true);
        if (aiCreate == null) {
            return send(frame, "ERR usage CREATE_AI <roomName> -- <prompt>");
        }
        return createAiRoom(frame, aiCreate, "ERR usage CREATE_AI <roomName> -- <prompt>");
    }

    private boolean createAiRoom(Frame frame, AiCreate aiCreate, String usage) throws IOException {
        OllamaClient ollama = state.ollamaClient();
        if (ollama == null) {
            return send(frame, "ERR AI rooms not available (Ollama not configured)");
        }

        try {
            state.rooms().createAI(aiCreate.roomName(), aiCreate.prompt(), ollama);
            return send(frame, "OK CREATED_AI " + Room.normalizeName(aiCreate.roomName()));
        } catch (IllegalArgumentException e) {
            if (aiCreate.roomName().isBlank() || aiCreate.prompt().isBlank()) return send(frame, usage);
            if (state.rooms().exists(aiCreate.roomName())) return send(frame, "ERR room exists");
            return send(frame, "ERR bad room name");
        }
    }

    private static AiCreate parseAiCreate(String tail, boolean allowBriefMarker, boolean allowLegacyOneWordRoom) {
        if (tail == null || tail.isBlank()) return null;

        int explicitSeparator = tail.indexOf(" -- ");
        if (explicitSeparator >= 0) {
            return aiCreateFromParts(
                    tail.substring(0, explicitSeparator),
                    tail.substring(explicitSeparator + " -- ".length()));
        }

        if (allowBriefMarker) {
            int marker = tail.lastIndexOf(" AI ");
            if (marker >= 0) {
                return aiCreateFromParts(
                        tail.substring(0, marker),
                        tail.substring(marker + " AI ".length()));
            }
        }

        if (allowLegacyOneWordRoom) {
            String[] parts = tail.split("\\s+", 2);
            if (parts.length == 2) return aiCreateFromParts(parts[0], parts[1]);
        }

        return null;
    }

    private static AiCreate aiCreateFromParts(String roomName, String prompt) {
        String room = roomName == null ? "" : roomName.trim();
        String aiPrompt = prompt == null ? "" : prompt.trim();
        if (room.isEmpty() || aiPrompt.isEmpty()) return null;
        return new AiCreate(room, aiPrompt);
    }

    private record AiCreate(String roomName, String prompt) {}

    private boolean handleJoin(Frame frame, String roomName) throws IOException {
        if (roomName == null || roomName.isBlank()) {
            return send(frame, "ERR usage JOIN <roomName>");
        }

        Room room;
        try {
            room = state.rooms().getOrCreateNormal(roomName);
        } catch (IllegalArgumentException e) {
            return send(frame, "ERR bad room name");
        }

        String currentRoom = session.currentRoomName();
        if (room.name().equals(currentRoom)) {
            return send(frame, "JOINED " + room.name());
        }

        leaveCurrentRoom();
        if (!send(frame, "JOINED " + room.name())) return false;
        session.enterRoom(room.name());
        room.join(session);
        return true;
    }

    private boolean handleMessage(Frame frame, String text) throws IOException {
        if (text == null || text.isBlank()) {
            return send(frame, "ERR usage MSG <text>");
        }

        String roomName = session.currentRoomName();
        if (roomName == null) return send(frame, "ERR not in room");

        Room room = state.rooms().get(roomName);
        if (room == null) {
            session.leaveRoom();
            return send(frame, "ERR not in room");
        }

        room.postUserMessage(session.username(), text);
        return true;
    }

    private boolean handleLeave(Frame frame) throws IOException {
        String previousRoom = session.currentRoomName();
        if (previousRoom == null) return send(frame, "ERR not in room");

        leaveCurrentRoom();
        return send(frame, "LEFT " + previousRoom);
    }

    private boolean handleRegister(Frame frame, String[] parts) throws IOException {
        if (parts.length != 3) {
            return send(frame, "ERR usage REGISTER <username> <password>");
        }
        if (session != null) {
            return send(frame, "ERR already authenticated");
        }

        String username = parts[1];
        char[] password = parts[2].toCharArray();
        try {
            boolean created = state.register(username, password);
            if (!created) return send(frame, "ERR user exists");
            return send(frame, "OK REGISTERED " + User.normalizeUsername(username));
        } catch (IllegalArgumentException e) {
            return send(frame, "ERR bad registration");
        } catch (IOException e) {
            return send(frame, "ERR registration failed");
        } finally {
            PasswordHasher.clear(password);
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
            if (!send(frame, "OK TOKEN " + created.tokenValue())) return false;
            return send(frame, roomListLine());
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
        long generation = attachConnection(resumed);
        resumed.clearOutbound();
        frame.writeLine("OK RESUMED " + resumed.username());
        enqueueResumeReplay(resumed);
        startWriter(resumed, generation, frame);
        return true;
    }

    private void leaveCurrentRoom() {
        String previousRoom = session.leaveRoom();
        if (previousRoom == null) return;

        Room room = state.rooms().get(previousRoom);
        if (room != null) room.leave(session);
    }

    private static String tail(String[] parts) {
        if (parts.length < 2) return null;
        if (parts.length == 2) return parts[1];
        return parts[1] + " " + parts[2];
    }

    private boolean send(Frame frame, String line) throws IOException {
        if (writerThread == null) {
            frame.writeLine(line);
            return true;
        }
        return session != null && session.enqueue(line);
    }

    private void startWriter(Session attachedSession, Frame frame) {
        long generation = attachConnection(attachedSession);
        startWriter(attachedSession, generation, frame);
    }

    private long attachConnection(Session attachedSession) {
        long generation = attachedSession.attachConnection();
        writerGeneration = generation;
        writerDrainThenStop = false;
        return generation;
    }

    private void startWriter(Session attachedSession, long generation, Frame frame) {
        Thread writer = Thread.ofVirtual()
                .name("session-writer-" + attachedSession.username())
                .start(() -> writerLoop(attachedSession, generation, frame));
        attachedSession.attachWriter(generation, writer);
        writerThread = writer;
    }

    private void writerLoop(Session attachedSession, long generation, Frame frame) {
        try {
            while (attachedSession.ownsConnection(generation)) {
                Session.OutboundFrame outbound = attachedSession.takeOutboundFrame();
                if (!attachedSession.ownsConnection(generation)) {
                    return;
                }

                frame.writeLine(outbound.line());
                if (outbound.hasRoomSequence()) {
                    attachedSession.markSeen(outbound.roomName(), outbound.seq());
                }
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

    private void enqueueResumeReplay(Session resumed) {
        String roomName = resumed.currentRoomName();
        if (roomName == null) return;

        Room room = state.rooms().get(roomName);
        if (room == null) {
            resumed.leaveRoom();
            resumed.enqueue("LEFT " + roomName);
            return;
        }

        long lastSeen = resumed.lastSeenSeq(room.name());
        var missed = room.historyAfter(lastSeen, room.historyLimit());
        resumed.enqueue("JOINED " + room.name());
        resumed.enqueue("HIST " + missed.size());
        for (RoomMessage message : missed) {
            resumed.enqueueRoomMessage(room.name(), message);
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
