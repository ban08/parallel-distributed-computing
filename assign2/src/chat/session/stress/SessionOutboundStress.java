package chat.session.stress;

import chat.auth.PasswordHasher;
import chat.auth.User;
import chat.session.Session;

/**
 * Many producers enqueue into a small session queue while one writer drains it.
 * This checks that slow-client backpressure is bounded and does not corrupt frames.
 */
public final class SessionOutboundStress {
    private static final int PRODUCERS = 40;
    private static final int PER_PRODUCER = 200;
    private static final int CAPACITY = 32;

    public static void main(String[] args) throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        Session session = new Session(new User("stress_user", hasher.hash("password123".toCharArray())), CAPACITY);

        Throwable[] failure = new Throwable[1];
        int[] accepted = new int[PRODUCERS];
        int[] drained = new int[1];

        Thread writer = Thread.ofVirtual().name("session-writer-drain").start(() -> {
            try {
                while (true) {
                    String frame = session.takeOutbound();
                    if ("STOP".equals(frame)) break;
                    if (!frame.startsWith("MSG ")) throw new AssertionError("corrupt frame: " + frame);
                    drained[0]++;
                }
            } catch (Throwable t) {
                failure[0] = t;
            }
        });

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final int pid = p;
            producers[p] = Thread.ofVirtual().name("session-producer-" + p).start(() -> {
                try {
                    int localAccepted = 0;
                    for (int i = 0; i < PER_PRODUCER; i++) {
                        if (session.enqueue("MSG p=" + pid + " i=" + i)) localAccepted++;
                        if ((i & 15) == 0) Thread.yield();
                    }
                    accepted[pid] = localAccepted;
                } catch (Throwable t) {
                    failure[0] = t;
                }
            });
        }

        for (Thread t : producers) t.join();
        session.putOutbound("STOP");
        writer.join();

        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(1);
        }

        int totalAccepted = 0;
        for (int n : accepted) totalAccepted += n;
        if (drained[0] != totalAccepted) {
            System.err.println("FAIL SessionOutboundStress: accepted=" + totalAccepted + " drained=" + drained[0]);
            System.exit(1);
        }
        if (session.outboundSize() != 0) {
            System.err.println("FAIL SessionOutboundStress: queue not empty, size=" + session.outboundSize());
            System.exit(1);
        }

        System.out.println("PASS SessionOutboundStress: accepted=" + totalAccepted + ", dropped="
                + (PRODUCERS * PER_PRODUCER - totalAccepted));
    }

    private SessionOutboundStress() {}
}
