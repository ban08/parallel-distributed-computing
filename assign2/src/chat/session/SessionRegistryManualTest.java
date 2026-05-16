package chat.session;

import chat.auth.PasswordHasher;
import chat.auth.User;

import java.time.Duration;

/** Basic executable checks for B4. */
public final class SessionRegistryManualTest {
    public static void main(String[] args) throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        User user = new User("miguel", hasher.hash("password123".toCharArray()));

        SessionRegistry registry = new SessionRegistry();
        Session session = registry.create(user, 8, Duration.ofMinutes(30));

        if (registry.size() != 1) throw new AssertionError("registry size should be 1");
        if (registry.lookup(session.tokenValue()) != session) throw new AssertionError("lookup by token failed");
        if (registry.lookup("missing-token") != null) throw new AssertionError("missing token should return null");

        Session removed = registry.remove(session.tokenValue());
        if (removed != session) throw new AssertionError("remove returned wrong session");
        if (!session.isClosed()) throw new AssertionError("removed session should be closed");
        if (registry.size() != 0) throw new AssertionError("registry should be empty after remove");

        Session shortLived = registry.create(user, 8, Duration.ofMillis(20));
        Thread.sleep(40);
        if (registry.lookup(shortLived.tokenValue()) != null) throw new AssertionError("expired token should not lookup");
        if (!shortLived.isClosed()) throw new AssertionError("expired lookup should close session");
        if (registry.size() != 0) throw new AssertionError("expired session should be removed");

        Session closeThenReap = registry.create(user, 8, Duration.ofMinutes(30));
        closeThenReap.close();
        int removedExpired = registry.removeExpired();
        if (removedExpired != 1) throw new AssertionError("removeExpired should remove closed session");
        if (registry.size() != 0) throw new AssertionError("registry should be empty after reap");

        System.out.println("PASS SessionRegistryManualTest");
    }

    private SessionRegistryManualTest() {}
}
