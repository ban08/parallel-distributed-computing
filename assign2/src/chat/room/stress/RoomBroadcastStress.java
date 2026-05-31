package chat.room.stress;

import chat.room.Room;
import chat.room.RoomKind;
import chat.room.RoomMessage;
import chat.room.RoomRegistry;
import chat.room.RoomSubscriber;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Executable room checks: system messages, history, snapshots, and broadcasts. */
public final class RoomBroadcastStress {
    private static final int SUBSCRIBERS = 12;
    private static final int PRODUCERS = 24;
    private static final int PER_PRODUCER = 200;

    public static void main(String[] args) throws Exception {
        smokeRoomTimeline();
        smokeOverflowDisconnect();
        stressConcurrentBroadcast();
        smokeRegistry();
        System.out.println("PASS RoomBroadcastStress");
    }

    private static void smokeOverflowDisconnect() {
        Room room = new Room("overflow");
        RejectingSubscriber slow = new RejectingSubscriber();
        room.join(slow);
        expect(slow.disconnects, 1L, "overflow disconnect count");
    }

    private static void smokeRoomTimeline() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1234L), ZoneOffset.UTC);
        Room room = new Room("Library", RoomKind.NORMAL, 10, clock);
        FakeSubscriber alice = new FakeSubscriber("alice");
        FakeSubscriber bob = new FakeSubscriber("bob");

        room.join(alice);
        room.join(bob);
        RoomMessage posted = room.postUserMessage("alice", "hello bob");
        room.leave(bob);

        if (posted.seq() != 3L) throw new AssertionError("bad user message seq: " + posted.seq());

        List<RoomMessage> all = room.recentHistory(100);
        expect(all.size(), 4L, "history size");
        expectText(all.get(0), "alice entered the room", true);
        expectText(all.get(1), "bob entered the room", true);
        expectText(all.get(2), "hello bob", false);
        expectText(all.get(3), "bob left the room", true);

        List<RoomMessage> tail = room.recentHistory(2);
        expect(tail.size(), 2L, "recent history size");
        expect(tail.get(0).seq(), 3L, "recent history first seq");

        if (alice.countFramesStartingWith("SYS ") != 3) {
            throw new AssertionError("alice should see three system messages");
        }
        if (bob.countFramesStartingWith("SYS ") != 1) {
            throw new AssertionError("bob should see only his join system message");
        }
    }

    private static void stressConcurrentBroadcast() throws Exception {
        Room room = new Room("stress", RoomKind.NORMAL, 10_000, Clock.systemUTC());
        List<FakeSubscriber> subscribers = new ArrayList<>();
        for (int i = 0; i < SUBSCRIBERS; i++) {
            FakeSubscriber subscriber = new FakeSubscriber("sub_" + i);
            subscribers.add(subscriber);
            room.join(subscriber);
        }

        Throwable[] failure = new Throwable[1];
        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final int pid = p;
            producers[p] = Thread.ofVirtual().name("room-prod-" + p).start(() -> {
                try {
                    for (int i = 0; i < PER_PRODUCER; i++) {
                        room.postUserMessage("user_" + pid, "m_" + pid + "_" + i);
                    }
                } catch (Throwable t) {
                    failure[0] = t;
                }
            });
        }

        for (Thread thread : producers) thread.join();
        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(1);
        }

        int expectedUserMessages = PRODUCERS * PER_PRODUCER;
        for (FakeSubscriber subscriber : subscribers) {
            int seen = subscriber.countFramesStartingWith("MSG ");
            if (seen != expectedUserMessages) {
                throw new AssertionError(subscriber.username() + " saw " + seen
                        + " user messages, expected " + expectedUserMessages);
            }
        }

        List<RoomMessage> history = room.recentHistory(10_000);
        expect(history.size(), SUBSCRIBERS + expectedUserMessages, "stress history size");
        long previous = 0L;
        List<String> expectedFrames = new ArrayList<>();
        for (RoomMessage message : history) {
            if (message.seq() <= previous) {
                throw new AssertionError("history out of order at seq " + message.seq());
            }
            if (!message.system()) expectedFrames.add(message.toFrame());
            previous = message.seq();
        }
        for (FakeSubscriber subscriber : subscribers) {
            if (!expectedFrames.equals(subscriber.framesStartingWith("MSG "))) {
                throw new AssertionError(subscriber.username() + " observed messages out of order");
            }
        }
    }

    private static void smokeRegistry() {
        RoomRegistry registry = new RoomRegistry();
        Room alpha = registry.create("Alpha");

        if (registry.get("Alpha") != alpha) throw new AssertionError("Alpha lookup failed");
        if (!List.of("Alpha").equals(registry.names())) {
            throw new AssertionError("bad registry names: " + registry.names());
        }

        try {
            registry.create("Alpha");
            throw new AssertionError("duplicate room creation should fail");
        } catch (IllegalArgumentException expected) {
            // expected path
        }
    }

    private static void expectText(RoomMessage message, String text, boolean system) {
        if (!text.equals(message.text()) || message.system() != system) {
            throw new AssertionError("bad message: " + message);
        }
    }

    private static void expect(long actual, long expected, String label) {
        if (actual != expected) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class FakeSubscriber implements RoomSubscriber {
        private final String username;
        private final ReentrantLock lock = new ReentrantLock();
        private final List<String> frames = new ArrayList<>();

        FakeSubscriber(String username) {
            this.username = username;
        }

        @Override
        public String username() {
            return username;
        }

        @Override
        public boolean enqueue(String frame) {
            lock.lock();
            try {
                frames.add(frame);
                return true;
            } finally {
                lock.unlock();
            }
        }

        int countFramesStartingWith(String prefix) {
            lock.lock();
            try {
                int count = 0;
                for (String frame : frames) {
                    if (frame.startsWith(prefix)) count++;
                }
                return count;
            } finally {
                lock.unlock();
            }
        }

        List<String> framesStartingWith(String prefix) {
            lock.lock();
            try {
                List<String> selected = new ArrayList<>();
                for (String frame : frames) {
                    if (frame.startsWith(prefix)) selected.add(frame);
                }
                return selected;
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class RejectingSubscriber implements RoomSubscriber {
        private int disconnects;

        @Override
        public String username() {
            return "slow";
        }

        @Override
        public boolean enqueue(String frame) {
            return false;
        }

        @Override
        public void disconnect() {
            disconnects++;
        }
    }

    private RoomBroadcastStress() {}
}
