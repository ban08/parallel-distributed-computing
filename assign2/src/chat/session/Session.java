package chat.session;

import chat.auth.User;
import chat.concurrent.BoundedQueue;
import chat.room.Room;
import chat.room.RoomSubscriber;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticated user session.
 *
 * A Session owns a secure token and a bounded outbound queue. Server code should
 * enqueue all outgoing frames here and let exactly one writer thread consume
 * takeOutbound() and write to the TCP connection.
 *
 * The logical session deliberately outlives any one TCP connection. Its token,
 * current room remains available while a broken transport is discarded and
 * replaced during resume. Pending output is deliberately discarded when a new
 * transport attaches: reconnect resumes live delivery without replay.
 */
public final class Session implements RoomSubscriber, AutoCloseable {
    private static final int OUTBOUND_CAPACITY = 256;

    private final User user;
    private final Token token;
    private final BoundedQueue<String> outbound;
    private final ReentrantLock stateLock = new ReentrantLock();
    private boolean closed;
    private long connectionGeneration;
    private Thread writerThread;
    /** Closes the currently attached Frame/socket without closing the session. */
    private Runnable connectionCloser;
    private String currentRoomName;

    Session(User user) {
        this.user = Objects.requireNonNull(user, "user");
        this.token = Token.issue();
        this.outbound = new BoundedQueue<>(OUTBOUND_CAPACITY);
    }

    @Override
    public String username() {
        return user.username();
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
     * Any previous transport is closed and its writer interrupted so it cannot
     * keep issuing commands or consuming outbound frames after a reconnect
     * attaches a newer socket.
     */
    public long attachConnection(Runnable closer) {
        Objects.requireNonNull(closer, "closer");

        Thread previousWriter;
        Runnable previousCloser;
        long generation;

        stateLock.lock();
        try {
            if (closed) throw new IllegalStateException("session is closed");
            generation = ++connectionGeneration;
            previousWriter = writerThread;
            previousCloser = connectionCloser;
            writerThread = null;
            connectionCloser = closer;
            outbound.drainTo(new ArrayList<>());
        } finally {
            stateLock.unlock();
        }

        closeConnection(previousCloser);
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
                // Advance again so no stale handler can continue routing input
                // after the writer for this transport exits.
                connectionGeneration++;
                writerThread = null;
                connectionCloser = null;
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void disconnect() {
        Thread writer;
        Runnable closer;
        stateLock.lock();
        try {
            if (closed || connectionCloser == null) return;
            // Invalidate reader and writer ownership but keep room/token state
            // intact so ConnectionManager can resume this logical session.
            connectionGeneration++;
            writer = writerThread;
            closer = connectionCloser;
            writerThread = null;
            connectionCloser = null;
        } finally {
            stateLock.unlock();
        }

        closeConnection(closer);
        if (writer != null) writer.interrupt();
    }

    public String currentRoomName() {
        stateLock.lock();
        try {
            return currentRoomName;
        } finally {
            stateLock.unlock();
        }
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

    /** Takes the next outbound frame, blocking while none is available. */
    public String takeOutbound() throws InterruptedException {
        return outbound.take();
    }

    public int outboundSize() {
        return outbound.size();
    }

    boolean isClosed() {
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
        Runnable closer;
        stateLock.lock();
        try {
            if (closed) return;
            closed = true;
            connectionGeneration++;
            writer = writerThread;
            closer = connectionCloser;
            writerThread = null;
            connectionCloser = null;
            outbound.drainTo(new ArrayList<>());
        } finally {
            stateLock.unlock();
        }
        closeConnection(closer);
        if (writer != null) writer.interrupt();
    }

    private static void closeConnection(Runnable closer) {
        if (closer == null) return;
        try {
            closer.run();
        } catch (RuntimeException ignored) {
            // A transport close failure must not keep the session attached.
        }
    }

    private static void validateFrame(String frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.indexOf('\n') >= 0 || frame.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("frame must not contain newline characters");
        }
    }

}
