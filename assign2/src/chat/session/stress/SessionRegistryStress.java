package chat.session.stress;

import chat.auth.User;
import chat.session.Session;
import chat.session.SessionRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Concurrent create/lookup/remove test for B4. */
public final class SessionRegistryStress {
    private static final int THREADS = 24;
    private static final int SESSIONS_PER_THREAD = 250;
    private static final String DUMMY_HASH = "pbkdf2$600000$dGVzdF9zYWx0XzEyMzQ1Ng==$dGVzdF9oYXNoXzEyMzQ1Ng==";

    public static void main(String[] args) throws Exception {
        SessionRegistry registry = new SessionRegistry();
        List<Thread> threads = new ArrayList<>();
        int[] created = new int[THREADS];
        int[] removed = new int[THREADS];

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            Thread thread = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < SESSIONS_PER_THREAD; i++) {
                    User user = new User("u" + threadId + "_" + i, DUMMY_HASH);
                    Session session = registry.create(user, 16, Duration.ofMinutes(5));
                    created[threadId]++;

                    Session lookedUp = registry.lookup(session.tokenValue());
                    if (lookedUp != session) {
                        throw new AssertionError("lookup returned wrong session");
                    }

                    if ((i & 1) == 0) {
                        Session old = registry.remove(session.tokenValue());
                        if (old != session) throw new AssertionError("remove returned wrong session");
                        if (!session.isClosed()) throw new AssertionError("removed session not closed");
                        removed[threadId]++;
                    }
                }
            });
            threads.add(thread);
        }

        for (Thread thread : threads) thread.join();

        int expectedCreated = THREADS * SESSIONS_PER_THREAD;
        int expectedRemoved = THREADS * ((SESSIONS_PER_THREAD + 1) / 2);
        int expectedRemaining = expectedCreated - expectedRemoved;
        int actualCreated = sum(created);
        int actualRemoved = sum(removed);

        if (actualCreated != expectedCreated) {
            throw new AssertionError("bad created count: " + actualCreated);
        }
        if (actualRemoved != expectedRemoved) {
            throw new AssertionError("bad removed count: " + actualRemoved);
        }
        if (registry.size() != expectedRemaining) {
            throw new AssertionError("bad remaining count: " + registry.size() + ", expected " + expectedRemaining);
        }

        for (String token : registry.tokens()) {
            Session session = registry.lookup(token);
            if (session == null) throw new AssertionError("remaining token did not lookup");
            if (session.isClosed()) throw new AssertionError("remaining session is closed");
        }

        int reaped = registry.removeExpired();
        if (reaped != 0) throw new AssertionError("unexpected expired sessions: " + reaped);

        System.out.println("PASS SessionRegistryStress: created=" + actualCreated
                + ", removed=" + actualRemoved
                + ", remaining=" + registry.size());
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    private SessionRegistryStress() {}
}
