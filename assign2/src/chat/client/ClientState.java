package chat.client;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Small shared client state updated by server frames and read by client tasks.
 *
 * The input loop, connection manager, and frame reader may run on different
 * virtual threads. Every field therefore lives behind one lock. Passwords are
 * intentionally absent: reconnect state consists only of a server-issued token.
 */
public final class ClientState {
    private final ReentrantLock lock = new ReentrantLock();
    private boolean running = true;
    /** Associates an asynchronous OK RESUMED response with a manual token attempt. */
    private String pendingResumeToken;
    private String token;
    private String currentRoom;

    public boolean isRunning() {
        lock.lock();
        try {
            return running;
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        lock.lock();
        try {
            running = false;
        } finally {
            lock.unlock();
        }
    }

    /** Records local intent before a manually supplied TOKEN command is sent. */
    public void rememberResumeAttempt(String attemptedToken) {
        Objects.requireNonNull(attemptedToken, "attemptedToken");
        lock.lock();
        try {
            pendingResumeToken = attemptedToken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies the state transition implied by one server frame.
     *
     * Rendering is handled separately by {@link ClientReader}; this method only
     * updates state needed by future commands and automatic reconnect.
     */
    public void observeServerFrame(String line) {
        Objects.requireNonNull(line, "line");

        lock.lock();
        try {
            if (line.startsWith("OK TOKEN ")) {
                token = line.substring("OK TOKEN ".length()).trim();
            } else if (line.startsWith("OK RESUMED ")) {
                if (pendingResumeToken != null) {
                    token = pendingResumeToken;
                    pendingResumeToken = null;
                }
            } else if (line.startsWith("JOINED ")) {
                currentRoom = line.substring("JOINED ".length()).trim();
            } else if (line.startsWith("LEFT ")) {
                currentRoom = null;
            } else if ("ERR invalid token".equals(line) || "ERR UNKNOWN_TOKEN".equals(line)) {
                // A token is an in-memory capability. If the server rejects it,
                // forget local session state and require a fresh login.
                token = null;
                currentRoom = null;
                pendingResumeToken = null;
            } else if ("BYE".equals(line)) {
                token = null;
                currentRoom = null;
                running = false;
            }
        } finally {
            lock.unlock();
        }
    }

    public String token() {
        lock.lock();
        try {
            return token;
        } finally {
            lock.unlock();
        }
    }

    public String currentRoom() {
        lock.lock();
        try {
            return currentRoom;
        } finally {
            lock.unlock();
        }
    }
}
