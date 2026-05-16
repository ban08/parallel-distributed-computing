package chat.server;

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

    private final Path usersPath;
    private final UserRegistry users;
    private final SessionRegistry sessions;
    private final RoomRegistry rooms;

    public ServerState(Path usersPath, UserRegistry users, SessionRegistry sessions) {
        this(usersPath, users, sessions, new RoomRegistry());
    }

    public ServerState(Path usersPath, UserRegistry users, SessionRegistry sessions, RoomRegistry rooms) {
        this.usersPath = Objects.requireNonNull(usersPath, "usersPath");
        this.users = Objects.requireNonNull(users, "users");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    /** Loads users from disk and creates empty session registry. */
    public static ServerState load(Path usersPath) throws IOException {
        Path path = (usersPath == null) ? DEFAULT_USERS_FILE : usersPath;
        PasswordHasher hasher = new PasswordHasher();
        UsersFile usersFile = new UsersFile(path, hasher);
        usersFile.createDemoUsersIfMissing();
        UserRegistry userRegistry = UserRegistry.loadFrom(usersFile);
        return new ServerState(path, userRegistry, new SessionRegistry());
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
}
