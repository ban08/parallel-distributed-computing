package chat.auth;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable authenticated user record. Passwords are never stored in plaintext. */
public record User(String username, String passwordHash) {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,32}");

    public User {
        username = normalizeUsername(username);
        Objects.requireNonNull(passwordHash, "passwordHash");
        if (passwordHash.isBlank()) throw new IllegalArgumentException("passwordHash cannot be blank");
    }

    public static String normalizeUsername(String username) {
        Objects.requireNonNull(username, "username");
        String u = username.trim();
        if (!USERNAME.matcher(u).matches()) {
            throw new IllegalArgumentException("username must be 3-32 chars: letters, digits or _");
        }
        return u;
    }
}
