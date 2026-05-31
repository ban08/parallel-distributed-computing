package chat.auth;

import chat.concurrent.LockedMap;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe user registry backed by LockedMap.
 *
 * Passwords are verified through PasswordHasher; plaintext passwords are never
 * stored in this object.
 */
public final class UserRegistry {
    private final LockedMap<String, User> users = new LockedMap<>();
    private final PasswordHasher hasher;

    private UserRegistry(PasswordHasher hasher) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public static UserRegistry loadFrom(UsersFile usersFile) throws IOException {
        Objects.requireNonNull(usersFile, "usersFile");
        UserRegistry registry = new UserRegistry(new PasswordHasher());
        registry.load(usersFile.load());
        return registry;
    }

    private void load(Map<String, User> loadedUsers) {
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
}
