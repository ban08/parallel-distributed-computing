package chat.session;

import chat.auth.User;
import chat.concurrent.BoundedQueue;
import chat.room.Room;
import chat.room.RoomSubscriber;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticated user session.
 *
 * A Session owns a secure token and a bounded outbound queue. Server code should
 * enqueue all outgoing frames here and let exactly one writer thread consume
 * takeOutbound()/pollOutbound() and write to the TCP connection.
 */
public final class Session implements RoomSubscriber, AutoCloseable {
    public static final int DEFAULT_OUTBOUND_CAPACITY = 256;

    private final User user;
    private final Token token;
    private final BoundedQueue<String> outbound;
    private final ReentrantLock stateLock = new ReentrantLock();
    private boolean closed;
    private long connectionGeneration;
    private Thread writerThread;
    private String currentRoomName;

    public Session(User user) {
        this(user, DEFAULT_OUTBOUND_CAPACITY, Token.issue());
    }

    public Session(User user, int outboundCapacity) {
        this(user, outboundCapacity, Token.issue());
    }

    public Session(User user, int outboundCapacity, Duration tokenTtl) {
        this(user, outboundCapacity, Token.issue(tokenTtl));
    }

    public Session(User user, int outboundCapacity, Token token) {
        this.user = Objects.requireNonNull(user, "user");
        this.token = Objects.requireNonNull(token, "token");
        this.outbound = new BoundedQueue<>(outboundCapacity);
    }

    public User user() {
        return user;
    }

    @Override
    public String username() {
        return user.username();
    }

    public Token token() {
        return token;
    }

    public String tokenValue() {
        return token.value();
    }

    public boolean tokenExpired() {
        return token.isExpired();
    }

    /**
     * Marks a new TCP connection as the active one for this session.
     *
     * Any previous writer is interrupted so it cannot keep consuming outbound
     * frames after a reconnect attaches a newer socket.
     */
    public long attachConnection() {
        Thread previousWriter;
        long generation;

        stateLock.lock();
        try {
            if (closed) throw new IllegalStateException("session is closed");
            generation = ++connectionGeneration;
            previousWriter = writerThread;
            writerThread = null;
        } finally {
            stateLock.unlock();
        }

        if (previousWriter != null) previousWriter.interrupt();
        return generation;
    }

    /** Records the virtual thread currently writing this connection. */
    public void attachWriter(long generation, Thread writer) {
        Objects.requireNonNull(writer, "writer");

        boolean stale;
        stateLock.lock();
        try {
            stale = closed || generation != connectionGeneration;
            if (!stale) writerThread = writer;
        } finally {
            stateLock.unlock();
        }

        if (stale) writer.interrupt();
    }

    public boolean ownsConnection(long generation) {
        stateLock.lock();
        try {
            return !closed && generation == connectionGeneration;
        } finally {
            stateLock.unlock();
        }
    }

    public void detachConnection(long generation) {
        stateLock.lock();
        try {
            if (generation == connectionGeneration) {
                connectionGeneration++;
                writerThread = null;
            }
        } finally {
            stateLock.unlock();
        }
    }

    public String currentRoomName() {
        stateLock.lock();
        try {
            return currentRoomName;
        } finally {
            stateLock.unlock();
        }
    }

    public boolean inRoom() {
        return currentRoomName() != null;
    }

    public void enterRoom(String roomName) {
        String normalized = Room.normalizeName(roomName);
        stateLock.lock();
        try {
            if (closed) throw new IllegalStateException("session is closed");
            currentRoomName = normalized;
        } finally {
            stateLock.unlock();
        }
    }

    public String leaveRoom() {
        stateLock.lock();
        try {
            String previous = currentRoomName;
            currentRoomName = null;
            return previous;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Enqueues a frame without blocking. Returns false if the session is closed
     * or the bounded queue is full.
     */
    @Override
    public boolean enqueue(String frame) {
        validateFrame(frame);
        stateLock.lock();
        try {
            if (closed) return false;
            return outbound.offer(frame);
        } finally {
            stateLock.unlock();
        }
    }

    /** Enqueues a frame, waiting for capacity. Mostly useful in tests/bootstrap. */
    public void putOutbound(String frame) throws InterruptedException {
        validateFrame(frame);
        stateLock.lock();
        try {
            if (closed) throw new IllegalStateException("session is closed");
        } finally {
            stateLock.unlock();
        }
        outbound.put(frame);
    }

    /** Takes the next outbound frame, blocking while none is available. */
    public String takeOutbound() throws InterruptedException {
        return outbound.take();
    }

    /** Polls the next outbound frame, returning null on timeout. */
    public String pollOutbound(long timeout, TimeUnit unit) throws InterruptedException {
        return outbound.poll(timeout, unit);
    }

    public int outboundSize() {
        return outbound.size();
    }

    public int outboundCapacity() {
        return outbound.capacity();
    }

    public boolean isClosed() {
        stateLock.lock();
        try {
            return closed;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void close() {
        Thread writer;
        stateLock.lock();
        try {
            closed = true;
            writer = writerThread;
            writerThread = null;
        } finally {
            stateLock.unlock();
        }
        if (writer != null) writer.interrupt();
    }

    private static void validateFrame(String frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.indexOf('\n') >= 0 || frame.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("frame must not contain newline characters");
        }
    }
}
