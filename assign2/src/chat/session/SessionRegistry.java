package chat.session;

import chat.auth.User;
import chat.concurrent.LockedMap;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe registry of authenticated sessions indexed by token value.
 *
 * This class is the server-side table that lets a reconnecting TCP client send
 * a token and recover its previous Session without sending the password again.
 */
public final class SessionRegistry {
    public static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(24);

    private final LockedMap<String, Session> byToken = new LockedMap<>();

    /** Creates a session with default queue capacity and default token TTL. */
    public Session create(User user) {
        return create(user, Session.DEFAULT_OUTBOUND_CAPACITY, DEFAULT_TOKEN_TTL);
    }

    /** Creates a session with a custom outbound queue capacity and default token TTL. */
    public Session create(User user, int outboundCapacity) {
        return create(user, outboundCapacity, DEFAULT_TOKEN_TTL);
    }

    /**
     * Creates and registers a new session.
     *
     * Token collisions are extremely unlikely, but the loop makes the operation
     * correct even if a collision ever happens.
     */
    public Session create(User user, int outboundCapacity, Duration tokenTtl) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(tokenTtl, "tokenTtl");

        while (true) {
            Session session = new Session(user, outboundCapacity, tokenTtl);
            Session existing = byToken.putIfAbsent(session.tokenValue(), session);
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

    /** Removes and closes this session, if it is currently registered. */
    public boolean remove(Session session) {
        Objects.requireNonNull(session, "session");
        Session removed = remove(session.tokenValue());
        return removed == session;
    }

    /** Removes every expired or closed session and returns the number removed. */
    public int removeExpired() {
        int removed = 0;
        for (String token : byToken.keys()) {
            Session session = byToken.get(token);
            if (session != null && (session.tokenExpired() || session.isClosed())) {
                if (byToken.remove(token) != null) {
                    session.close();
                    removed++;
                }
            }
        }
        return removed;
    }

    public int size() {
        return byToken.size();
    }

    public Set<String> tokens() {
        return byToken.keys();
    }

    public Map<String, Session> snapshot() {
        return byToken.snapshot();
    }

    private static String normalizeToken(String tokenValue) {
        if (tokenValue == null) return null;
        String token = tokenValue.trim();
        return token.isEmpty() ? null : token;
    }
}
