package chat.session;

import chat.auth.User;
import chat.concurrent.LockedMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe registry of authenticated sessions indexed by token value.
 *
 * This class is the server-side table that lets a reconnecting TCP client send
 * a token and recover its previous Session without sending the password again.
 */
public final class SessionRegistry {
    private final LockedMap<String, Session> byToken = new LockedMap<>();

    /** Creates and registers a session. */
    public Session create(User user) {
        Objects.requireNonNull(user, "user");

        while (true) {
            Session session = new Session(user);
            Session existing = byToken.putIfAbsent(session.tokenValue(), session);
            // Collision is cryptographically improbable, but retrying makes the
            // registry correct without relying on probability for uniqueness.
            if (existing == null) return session;
        }
    }

    /**
     * Looks up a live session by token value.
     *
     * Expired sessions are removed and closed before returning null.
     */
    public Session lookup(String tokenValue) {
        String token = normalizeToken(tokenValue);
        if (token == null) return null;

        Session session = byToken.get(token);
        if (session == null) return null;

        if (session.tokenExpired() || session.isClosed()) {
            remove(token);
            return null;
        }
        return session;
    }

    /** Removes and closes the session with this token, if it exists. */
    public Session remove(String tokenValue) {
        String token = normalizeToken(tokenValue);
        if (token == null) return null;

        Session removed = byToken.remove(token);
        if (removed != null) removed.close();
        return removed;
    }

    /** Removes and closes every expired or closed session. */
    public List<Session> removeExpired() {
        List<Session> removed = new ArrayList<>();
        // keys() is a defensive snapshot, so removing entries during iteration
        // cannot invalidate the traversal.
        for (String token : byToken.keys()) {
            Session session = byToken.get(token);
            if (session != null && (session.tokenExpired() || session.isClosed())) {
                if (byToken.remove(token) != null) {
                    session.close();
                    removed.add(session);
                }
            }
        }
        return removed;
    }

    private static String normalizeToken(String tokenValue) {
        if (tokenValue == null) return null;
        String token = tokenValue.trim();
        return token.isEmpty() ? null : token;
    }
}
