package chat.room;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One chat room timeline.
 *
 * The room lock protects subscribers, history, and sequence allocation. Socket
 * writes are never performed here; broadcasts enqueue frames after the lock is
 * released so slow clients cannot stall room progress.
 */
public class Room {
    public static final int DEFAULT_HISTORY_LIMIT = 200;

    private final String name;
    private final RoomKind kind;
    private final int historyLimit;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition broadcastTurn = lock.newCondition();
    private final Set<RoomSubscriber> subscribers = new HashSet<>();
    private final ArrayDeque<RoomMessage> history;
    private long nextSeq = 1L;
    private long nextBroadcastSeq = 1L;

    public Room(String name) {
        this(name, RoomKind.NORMAL);
    }

    public Room(String name, RoomKind kind) {
        this(name, kind, DEFAULT_HISTORY_LIMIT, Clock.systemUTC());
    }

    public Room(String name, RoomKind kind, int historyLimit, Clock clock) {
        this.name = normalizeName(name);
        this.kind = Objects.requireNonNull(kind, "kind");
        if (historyLimit <= 0) throw new IllegalArgumentException("historyLimit must be positive");
        this.historyLimit = historyLimit;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.history = new ArrayDeque<>(historyLimit);
    }

    public String name() {
        return name;
    }

    public RoomKind kind() {
        return kind;
    }

    public int historyLimit() {
        return historyLimit;
    }

    /** Returns the display name for room listings (AI rooms get an [AI] suffix). */
    public String listEntry() {
        return kind == RoomKind.AI ? name + "[AI]" : name;
    }

    public long latestSeq() {
        lock.lock();
        try {
            return nextSeq - 1L;
        } finally {
            lock.unlock();
        }
    }

    public int subscriberCount() {
        lock.lock();
        try {
            return subscribers.size();
        } finally {
            lock.unlock();
        }
    }

    public void join(RoomSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        String username = cleanSubscriberName(subscriber.username());

        RoomMessage message = null;
        List<RoomSubscriber> targets = List.of();
        lock.lock();
        try {
            if (subscribers.add(subscriber)) {
                message = appendSystemMessage(username + " entered the room");
                targets = subscribersSnapshotLocked();
            }
        } finally {
            lock.unlock();
        }

        broadcastInOrder(targets, message);
    }

    public void leave(RoomSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        String username = cleanSubscriberName(subscriber.username());

        RoomMessage message = null;
        List<RoomSubscriber> targets = List.of();
        lock.lock();
        try {
            if (subscribers.remove(subscriber)) {
                message = appendSystemMessage(username + " left the room");
                targets = subscribersSnapshotLocked();
            }
        } finally {
            lock.unlock();
        }

        broadcastInOrder(targets, message);
    }

    public RoomMessage postUserMessage(String author, String text) {
        RoomMessage message;
        List<RoomSubscriber> targets;
        lock.lock();
        try {
            message = appendMessage(author, text, false);
            targets = subscribersSnapshotLocked();
        } finally {
            lock.unlock();
        }

        broadcastInOrder(targets, message);
        afterUserMessage(message);
        return message;
    }

    public List<RoomMessage> historyAfter(long lastSeenSeq, int maxCount) {
        if (lastSeenSeq < 0L) throw new IllegalArgumentException("lastSeenSeq cannot be negative");
        if (maxCount < 0) throw new IllegalArgumentException("maxCount cannot be negative");

        lock.lock();
        try {
            List<RoomMessage> selected = new ArrayList<>();
            if (maxCount == 0) return selected;

            for (RoomMessage message : history) {
                if (message.seq() > lastSeenSeq) selected.add(message);
            }
            if (selected.size() <= maxCount) return selected;

            return new ArrayList<>(selected.subList(selected.size() - maxCount, selected.size()));
        } finally {
            lock.unlock();
        }
    }

    public List<RoomMessage> recentHistory(int maxCount) {
        if (maxCount < 0) throw new IllegalArgumentException("maxCount cannot be negative");
        lock.lock();
        try {
            int size = history.size();
            int take = Math.min(size, maxCount);
            if (take == 0) return List.of();

            // Iterate backwards to grab only the tail entries
            RoomMessage[] tail = new RoomMessage[take];
            var it = history.descendingIterator();
            for (int i = take - 1; i >= 0 && it.hasNext(); i--) {
                tail[i] = it.next();
            }
            return List.of(tail);
        } finally {
            lock.unlock();
        }
    }

    public List<RoomSubscriber> subscribersSnapshot() {
        lock.lock();
        try {
            return subscribersSnapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    protected void afterUserMessage(RoomMessage message) {
        // AI rooms will hook here without changing normal room broadcast logic.
    }

    protected RoomMessage postSystemMessage(String text) {
        RoomMessage message;
        List<RoomSubscriber> targets;
        lock.lock();
        try {
            message = appendSystemMessage(text);
            targets = subscribersSnapshotLocked();
        } finally {
            lock.unlock();
        }

        broadcastInOrder(targets, message);
        return message;
    }

    private RoomMessage appendSystemMessage(String text) {
        return appendMessage("", text, true);
    }

    private RoomMessage appendMessage(String author, String text, boolean system) {
        RoomMessage message = new RoomMessage(nextSeq++, author, clock.millis(), text, system);
        history.addLast(message);
        while (history.size() > historyLimit) history.removeFirst();
        return message;
    }

    private List<RoomSubscriber> subscribersSnapshotLocked() {
        return new ArrayList<>(subscribers);
    }

    private void broadcastInOrder(List<RoomSubscriber> targets, RoomMessage message) {
        if (message == null) return;

        waitForBroadcastTurn(message.seq());
        try {
            if (!targets.isEmpty()) broadcast(targets, message);
        } finally {
            completeBroadcast(message.seq());
        }
    }

    private void waitForBroadcastTurn(long seq) {
        lock.lock();
        try {
            while (seq != nextBroadcastSeq) broadcastTurn.awaitUninterruptibly();
        } finally {
            lock.unlock();
        }
    }

    private void completeBroadcast(long seq) {
        lock.lock();
        try {
            if (seq == nextBroadcastSeq) {
                nextBroadcastSeq++;
                broadcastTurn.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private void broadcast(List<RoomSubscriber> targets, RoomMessage message) {
        for (RoomSubscriber target : targets) {
            try {
                target.enqueueRoomMessage(name, message);
            } catch (RuntimeException ignored) {
                // A broken subscriber must not prevent delivery to the rest.
            }
        }
    }

    public static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        String value = name.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("room name cannot be blank");
        if (hasFrameBreak(value)) throw new IllegalArgumentException("room name cannot contain newline characters");
        return value;
    }

    private static boolean hasFrameBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static String cleanSubscriberName(String username) {
        Objects.requireNonNull(username, "subscriber username");
        String value = username.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("subscriber username cannot be blank");
        if (hasFrameBreak(value)) throw new IllegalArgumentException("subscriber username cannot contain newline characters");
        return value;
    }
}
