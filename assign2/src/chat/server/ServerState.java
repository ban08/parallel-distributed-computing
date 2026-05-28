package chat.server;

import chat.ai.OllamaClient;
import chat.auth.PasswordHasher;
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
 * C1 keeps process-wide registries in one place so every TCP connection handler
 * uses the same users and sessions tables.
 */
public final class ServerState {
    public static final Path DEFAULT_USERS_FILE = Path.of("data", "users.txt");
    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final String DEFAULT_OLLAMA_MODEL = "llama3";

    private final Path usersPath;
    private final UserRegistry users;
    private final SessionRegistry sessions;
    private final RoomRegistry rooms;
    private final OllamaClient ollamaClient; // null if AI rooms disabled

    public ServerState(Path usersPath, UserRegistry users, SessionRegistry sessions) {
        this(usersPath, users, sessions, new RoomRegistry(), null);
    }

    public ServerState(Path usersPath, UserRegistry users, SessionRegistry sessions, RoomRegistry rooms) {
        this(usersPath, users, sessions, rooms, null);
    }

    public ServerState(Path usersPath, UserRegistry users, SessionRegistry sessions,
                       RoomRegistry rooms, OllamaClient ollamaClient) {
        this.usersPath = Objects.requireNonNull(usersPath, "usersPath");
        this.users = Objects.requireNonNull(users, "users");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.ollamaClient = ollamaClient; // nullable — AI rooms are optional
    }

    /** Loads users from disk and creates empty session registry. */
    public static ServerState load(Path usersPath) throws IOException {
        return load(usersPath, null, null);
    }

    /**
     * Loads users from disk and creates the Ollama client.
     *
     * @param ollamaUrl   Ollama base URL (null → default {@value DEFAULT_OLLAMA_URL})
     * @param ollamaModel Ollama model name (null → default {@value DEFAULT_OLLAMA_MODEL})
     */
    public static ServerState load(Path usersPath, String ollamaUrl, String ollamaModel) throws IOException {
        Path path = (usersPath == null) ? DEFAULT_USERS_FILE : usersPath;
        PasswordHasher hasher = new PasswordHasher();
        UsersFile usersFile = new UsersFile(path, hasher);
        usersFile.createDemoUsersIfMissing();
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

    public SessionRegistry sessions() {
        return sessions;
    }

    public RoomRegistry rooms() {
        return rooms;
    }

    /** Returns the Ollama client, or null if AI rooms are not configured. */
    public OllamaClient ollamaClient() {
        return ollamaClient;
    }

    /** Returns a newly-created session on successful login, otherwise null. */
    public Session login(String username, char[] password) {
        Objects.requireNonNull(password, "password");
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
        return sessions.lookup(token);
    }

    /** Registers a new user in the in-memory registry and backing users file. */
    public boolean register(String username, char[] password) throws IOException {
        return users.register(username, password);
    }
}
