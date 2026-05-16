package chat.auth;

import chat.concurrent.LockedMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe user registry backed by LockedMap.
 *
 * Passwords are verified through PasswordHasher; plaintext passwords are never
 * stored in this object. Registration can optionally be persisted through a
 * UsersFile.
 */
public final class UserRegistry {
    private final LockedMap<String, User> users = new LockedMap<>();
    private final PasswordHasher hasher;
    private final UsersFile usersFile;
    private final ReentrantLock persistLock = new ReentrantLock();

    public UserRegistry(PasswordHasher hasher) {
        this(hasher, null);
    }

    public UserRegistry(PasswordHasher hasher, UsersFile usersFile) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.usersFile = usersFile;
    }

    public static UserRegistry loadFrom(UsersFile usersFile) throws IOException {
        Objects.requireNonNull(usersFile, "usersFile");
        UserRegistry registry = new UserRegistry(new PasswordHasher(), usersFile);
        registry.load(usersFile.load());
        return registry;
    }

    public static UserRegistry loadFrom(Path path) throws IOException {
        PasswordHasher hasher = new PasswordHasher();
        UsersFile usersFile = new UsersFile(path, hasher);
        UserRegistry registry = new UserRegistry(hasher, usersFile);
        registry.load(usersFile.load());
        return registry;
    }

    public void load(Map<String, User> loadedUsers) {
        Objects.requireNonNull(loadedUsers, "loadedUsers");
        for (User user : loadedUsers.values()) {
            String username = User.normalizeUsername(user.username());
            User previous = users.putIfAbsent(username, user);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate user: " + username);
            }
        }
    }

    /** Returns true only when username exists and password matches its stored hash. */
    public boolean verify(String username, char[] password) {
        Objects.requireNonNull(password, "password");

        User user;
        try {
            user = users.get(User.normalizeUsername(username));
        } catch (IllegalArgumentException e) {
            return false;
        }

        return user != null && hasher.verify(password, user.passwordHash());
    }

    /**
     * Registers a new user. If this registry has a UsersFile, the change is
     * persisted atomically from the registry's point of view.
     *
     * @return true if the user was created, false if it already existed
     */
    public boolean register(String username, char[] password) throws IOException {
        Objects.requireNonNull(password, "password");
        String normalized = User.normalizeUsername(username);

        persistLock.lock();
        try {
            if (users.containsKey(normalized)) return false;

            User created = new User(normalized, hasher.hash(password));
            users.put(normalized, created);

            if (usersFile != null) {
                try {
                    usersFile.save(snapshotSorted());
                } catch (IOException | RuntimeException e) {
                    users.remove(normalized);
                    throw e;
                }
            }
            return true;
        } finally {
            persistLock.unlock();
        }
    }

    public boolean exists(String username) {
        try {
            return users.containsKey(User.normalizeUsername(username));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public User get(String username) {
        try {
            return users.get(User.normalizeUsername(username));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public int size() {
        return users.size();
    }

    public Set<String> usernames() {
        return users.keys();
    }

    public Map<String, User> snapshot() {
        return users.snapshot();
    }

    private Map<String, User> snapshotSorted() {
        List<User> list = new ArrayList<>(users.snapshot().values());
        list.sort(Comparator.comparing(User::username));

        Map<String, User> sorted = new LinkedHashMap<>();
        for (User user : list) sorted.put(user.username(), user);
        return sorted;
    }
}
