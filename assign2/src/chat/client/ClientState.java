package chat.client;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Small shared client state updated by reader frames and read by input commands. */
public final class ClientState {
    private final ReentrantLock lock = new ReentrantLock();
    private boolean running = true;
    private String username;
    private String pendingUsername;
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

    public void rememberLoginAttempt(String attemptedUsername) {
        Objects.requireNonNull(attemptedUsername, "attemptedUsername");
        lock.lock();
        try {
            pendingUsername = attemptedUsername;
        } finally {
            lock.unlock();
        }
    }

    public void rememberResumeAttempt(String attemptedToken) {
        Objects.requireNonNull(attemptedToken, "attemptedToken");
        lock.lock();
        try {
            pendingResumeToken = attemptedToken;
        } finally {
            lock.unlock();
        }
    }

    public void observeServerFrame(String line) {
        Objects.requireNonNull(line, "line");

        lock.lock();
        try {
            if (line.startsWith("OK TOKEN ")) {
                token = line.substring("OK TOKEN ".length()).trim();
                username = pendingUsername;
                pendingUsername = null;
            } else if (line.startsWith("OK RESUMED ")) {
                username = line.substring("OK RESUMED ".length()).trim();
                if (pendingResumeToken != null) {
                    token = pendingResumeToken;
                    pendingResumeToken = null;
                }
            } else if (line.startsWith("OK USER ")) {
                username = line.substring("OK USER ".length()).trim();
            } else if (line.startsWith("JOINED ")) {
                currentRoom = line.substring("JOINED ".length()).trim();
            } else if (line.startsWith("LEFT ")) {
                currentRoom = null;
            } else if ("ERR invalid token".equals(line) || "ERR UNKNOWN_TOKEN".equals(line)) {
                username = null;
                token = null;
                currentRoom = null;
                pendingResumeToken = null;
            } else if ("BYE".equals(line)) {
                running = false;
            }
        } finally {
            lock.unlock();
        }
    }

    public String username() {
        lock.lock();
        try {
            return username;
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
