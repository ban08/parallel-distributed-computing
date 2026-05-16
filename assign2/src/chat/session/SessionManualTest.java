package chat.session;

import chat.auth.PasswordHasher;
import chat.auth.User;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Basic executable checks for B3. */
public final class SessionManualTest {
    public static void main(String[] args) throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        User user = new User("miguel", hasher.hash("password123".toCharArray()));

        Session session = new Session(user, 2, Duration.ofMinutes(30));
        if (!"miguel".equals(session.username())) throw new AssertionError("bad username");
        if (session.tokenValue().length() < 32) throw new AssertionError("token too short");
        if (session.tokenExpired()) throw new AssertionError("new token is expired");

        if (!session.enqueue("OK TOKEN " + session.tokenValue())) throw new AssertionError("enqueue failed");
        if (!session.enqueue("PONG")) throw new AssertionError("second enqueue failed");
        if (session.enqueue("QUEUE_SHOULD_BE_FULL")) throw new AssertionError("queue capacity ignored");

        String first = session.takeOutbound();
        if (!first.startsWith("OK TOKEN ")) throw new AssertionError("bad first frame: " + first);
        if (!"PONG".equals(session.takeOutbound())) throw new AssertionError("bad second frame");
        if (session.pollOutbound(50, TimeUnit.MILLISECONDS) != null) throw new AssertionError("poll should timeout");

        Set<String> tokenValues = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String value = Token.issue().value();
            if (!tokenValues.add(value)) throw new AssertionError("duplicate token generated");
        }

        Token shortLived = Token.issue(Duration.ofMillis(20));
        Thread.sleep(40);
        if (!shortLived.isExpired(Instant.now())) throw new AssertionError("token should expire");

        Session attached = new Session(user, 2, Duration.ofMinutes(30));
        long firstGeneration = attached.attachConnection();
        boolean[] interrupted = new boolean[1];
        Thread writer = Thread.ofVirtual().name("manual-session-writer").start(() -> {
            try {
                attached.takeOutbound();
                throw new AssertionError("stale writer should have been interrupted");
            } catch (InterruptedException e) {
                interrupted[0] = true;
                Thread.currentThread().interrupt();
            }
        });
        attached.attachWriter(firstGeneration, writer);

        long secondGeneration = attached.attachConnection();
        writer.join(1000);
        if (writer.isAlive()) throw new AssertionError("previous writer was not interrupted");
        if (!interrupted[0]) throw new AssertionError("previous writer did not observe interrupt");
        if (attached.ownsConnection(firstGeneration)) throw new AssertionError("stale generation still owns session");
        if (!attached.ownsConnection(secondGeneration)) throw new AssertionError("new generation should own session");
        attached.detachConnection(secondGeneration);
        if (attached.ownsConnection(secondGeneration)) throw new AssertionError("detached generation still owns session");

        session.close();
        if (!session.isClosed()) throw new AssertionError("session should be closed");
        if (session.enqueue("AFTER_CLOSE")) throw new AssertionError("closed session accepted frame");

        System.out.println("PASS SessionManualTest");
    }

    private SessionManualTest() {}
}
