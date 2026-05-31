package chat.server;

import chat.ai.OllamaClient;
import chat.auth.User;
import chat.auth.UserRegistry;
import chat.auth.UsersFile;
import chat.room.RoomRegistry;
import chat.session.Session;
import chat.session.SessionRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared server state.
 *
 * Process-wide registries live here so every per-socket connection handler sees
 * the same users, resumable sessions, and rooms. The object also centralizes
 * startup wiring for persistent users and the local Ollama endpoint.
 */
public final class ServerState {
    public static final Path DEFAULT_USERS_FILE = Path.of("data", "users.txt");
    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final String DEFAULT_OLLAMA_MODEL = "llama3";

    private final Path usersPath;
    private final UserRegistry users;
    private final SessionRegistry sessions;
    private final RoomRegistry rooms;
    private final OllamaClient ollamaClient;

    private ServerState(Path usersPath, UserRegistry users, SessionRegistry sessions,
                        RoomRegistry rooms, OllamaClient ollamaClient) {
        this.usersPath = Objects.requireNonNull(usersPath, "usersPath");
        this.users = Objects.requireNonNull(users, "users");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.ollamaClient = Objects.requireNonNull(ollamaClient, "ollamaClient");
    }

    /**
     * Loads users from disk and creates the Ollama client.
     *
     * @param ollamaUrl   Ollama base URL (null → default {@value DEFAULT_OLLAMA_URL})
     * @param ollamaModel Ollama model name (null → default {@value DEFAULT_OLLAMA_MODEL})
     */
    public static ServerState load(Path usersPath, String ollamaUrl, String ollamaModel) throws IOException {
        Path path = (usersPath == null) ? DEFAULT_USERS_FILE : usersPath;
        UsersFile usersFile = new UsersFile(path);
        UserRegistry userRegistry = UserRegistry.loadFrom(usersFile);

        String url = (ollamaUrl != null && !ollamaUrl.isBlank()) ? ollamaUrl : DEFAULT_OLLAMA_URL;
        String model = (ollamaModel != null && !ollamaModel.isBlank()) ? ollamaModel : DEFAULT_OLLAMA_MODEL;
        OllamaClient ollama = new OllamaClient(url, model);

        return new ServerState(path, userRegistry, new SessionRegistry(), new RoomRegistry(), ollama);
    }

    public Path usersPath() {
        return usersPath;
    }

    public UserRegistry users() {
        return users;
    }

    public RoomRegistry rooms() {
        return rooms;
    }

    /** Returns the local Ollama adapter used for AI rooms. */
    public OllamaClient ollamaClient() {
        return ollamaClient;
    }

    /** Returns a newly-created session on successful login, otherwise null. */
    public Session login(String username, char[] password) {
        Objects.requireNonNull(password, "password");
        cleanupExpiredSessions();
        try {
            if (!users.verify(username, password)) return null;
            User user = users.get(username);
            if (user == null) return null;
            return sessions.create(user);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Returns an existing live session for a reconnecting token, otherwise null. */
    public Session resume(String token) {
        cleanupExpiredSessions();
        return sessions.lookup(token);
    }

    /** Invalidates a session after an explicit client logout. */
    public void logout(Session session) {
        Objects.requireNonNull(session, "session");
        Session removed = sessions.remove(session.tokenValue());
        if (removed != null) detachFromRoom(removed);
    }

    /** Opportunistically reaps expired sessions whenever authentication is used. */
    private int cleanupExpiredSessions() {
        var removed = sessions.removeExpired();
        for (Session session : removed) detachFromRoom(session);
        return removed.size();
    }

    private void detachFromRoom(Session session) {
        String roomName = session.leaveRoom();
        if (roomName == null) return;

        var room = rooms.get(roomName);
        if (room != null) room.leave(session);
    }
}
