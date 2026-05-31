package chat.room;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * One chat room timeline.
 *
 * The room lock protects subscribers, history, and sequence allocation. Socket
 * writes are never performed here. Broadcasts only make non-blocking queue
 * offers while holding the lock, which preserves room order without letting a
 * slow client stall room progress.
 */
public class Room {
    public static final int DEFAULT_HISTORY_LIMIT = 200;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final String name;
    private final RoomKind kind;
    private final int historyLimit;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<RoomSubscriber> subscribers = new HashSet<>();
    private final ArrayDeque<RoomMessage> history;
    /** Next sequence allocated to a newly appended timeline entry. */
    private long nextSeq = 1L;

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

    public int historyLimit() {
        return historyLimit;
    }

    /** Returns the display name for room listings (AI rooms get an [AI] suffix). */
    public String listEntry() {
        return kind == RoomKind.AI ? name + "[AI]" : name;
    }

    /** Adds a subscriber once and broadcasts the resulting timeline event. */
    public void join(RoomSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        String username = cleanSubscriberName(subscriber.username());

        List<RoomSubscriber> rejected = List.of();
        lock.lock();
        try {
            if (subscribers.add(subscriber)) {
                rejected = broadcastLocked(appendSystemMessage(username + " entered the room"));
            }
        } finally {
            lock.unlock();
        }
        disconnectRejected(rejected);
    }

    /** Removes a subscriber once and broadcasts the resulting timeline event. */
    public void leave(RoomSubscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        String username = cleanSubscriberName(subscriber.username());

        List<RoomSubscriber> rejected = List.of();
        lock.lock();
        try {
            if (subscribers.remove(subscriber)) {
                rejected = broadcastLocked(appendSystemMessage(username + " left the room"));
            }
        } finally {
            lock.unlock();
        }
        disconnectRejected(rejected);
    }

    /** Appends, broadcasts, and then invokes the AI-room extension hook. */
    public RoomMessage postUserMessage(String author, String text) {
        RoomMessage message;
        List<RoomSubscriber> rejected;
        lock.lock();
        try {
            message = appendMessage(author, text, false);
            rejected = broadcastLocked(message);
        } finally {
            lock.unlock();
        }

        disconnectRejected(rejected);
        afterUserMessage(message);
        return message;
    }

    /** Returns up to maxCount recent retained messages in chronological order. */
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

    /** Extension point used by AIRoom after normal broadcast has completed. */
    protected void afterUserMessage(RoomMessage message) {
        // AI rooms will hook here without changing normal room broadcast logic.
    }

    protected RoomMessage postSystemMessage(String text) {
        RoomMessage message;
        List<RoomSubscriber> rejected;
        lock.lock();
        try {
            message = appendSystemMessage(text);
            rejected = broadcastLocked(message);
        } finally {
            lock.unlock();
        }

        disconnectRejected(rejected);
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

    /** Enqueues in timeline order while the room lock is held. Queue offers never block. */
    private List<RoomSubscriber> broadcastLocked(RoomMessage message) {
        List<RoomSubscriber> rejected = new ArrayList<>();
        for (RoomSubscriber target : subscribers) {
            try {
                if (!target.enqueue(message.toFrame())) rejected.add(target);
            } catch (RuntimeException ignored) {
                // A broken subscriber must not prevent delivery to the rest.
            }
        }
        return rejected;
    }

    private void disconnectRejected(List<RoomSubscriber> rejected) {
        for (RoomSubscriber subscriber : rejected) {
            // Disconnect outside the room lock: closing a socket is not part of
            // the room critical section. The token can still resume live traffic.
            subscriber.disconnect();
        }
    }

    public static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        String value = name.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("room name cannot be blank");
        if (!VALID_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("room name must contain only letters, digits, '_' or '-'");
        }
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
