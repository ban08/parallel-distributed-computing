package chat.client;

import chat.common.Frame;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the client's current transport and automatically reconnects.
 *
 * Exactly one {@link Frame} is publishable through {@link #send}. The
 * connection lock makes replacement, writes, and shutdown atomic with respect
 * to one another. After TCP failure, the loop opens a new socket and sends the
 * remembered token before reading normal traffic.
 */
public final class ConnectionManager implements Runnable {
    private final String host;
    private final int port;
    private final ClientState state;
    private final PrintStream out;
    private final PrintStream err;
    private final ReconnectBackoff backoff;
    private final ReentrantLock connectionLock = new ReentrantLock();
    private Frame currentFrame;

    public ConnectionManager(String host, int port, ClientState state, PrintStream out, PrintStream err) {
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port out of range");
        this.port = port;
        this.state = Objects.requireNonNull(state, "state");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.backoff = new ReconnectBackoff();
    }

    public Thread start() {
        return Thread.ofVirtual().name("client-connection-manager").start(this);
    }

    @Override
    public void run() {
        while (state.isRunning()) {
            try (Socket socket = new Socket(host, port);
                 Frame frame = new Frame(socket)) {
                // Publish the frame only after the socket and UTF-8 streams are
                // ready. User commands may begin using it immediately.
                installFrame(frame);
                backoff.reset();
                out.println("[client] connected to " + host + ":" + port);
                resumeIfPossible();

                // Run the reader inline: this virtual thread owns the complete
                // lifetime of the current transport before reconnecting.
                new ClientReader(frame, state, out, err).run();
            } catch (IOException e) {
                if (state.isRunning()) err.println("[client] connection failed: " + e.getMessage());
            } finally {
                clearFrame();
            }

            if (state.isRunning()) sleepBeforeReconnect();
        }
    }

    /**
     * Writes one protocol frame if a transport is currently installed.
     *
     * Holding connectionLock makes BufferedWriter single-writer and prevents a
     * reconnect or stop operation from closing the frame halfway through use.
     */
    public boolean send(String line) {
        connectionLock.lock();
        try {
            if (currentFrame == null) return false;
            try {
                currentFrame.writeLine(line);
                return true;
            } catch (IOException | RuntimeException e) {
                closeQuietly(currentFrame);
                currentFrame = null;
                return false;
            }
        } finally {
            connectionLock.unlock();
        }
    }

    /** Stops reconnect attempts and closes the current transport, if any. */
    public void stop() {
        state.stop();
        connectionLock.lock();
        try {
            closeQuietly(currentFrame);
            currentFrame = null;
        } finally {
            connectionLock.unlock();
        }
    }

    /** Sends a remembered capability before ordinary traffic on a new socket. */
    private void resumeIfPossible() {
        String token = state.token();
        if (token == null) return;
        out.println("[client] resuming previous session...");
        if (!send("TOKEN " + token)) {
            err.println("[client] could not send resume token");
        }
    }

    private void sleepBeforeReconnect() {
        long delay = backoff.nextDelayMillis();
        err.println("[client] reconnecting in " + delay + " ms");
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.stop();
        }
    }

    private void installFrame(Frame frame) {
        connectionLock.lock();
        try {
            currentFrame = frame;
        } finally {
            connectionLock.unlock();
        }
    }

    private void clearFrame() {
        connectionLock.lock();
        try {
            currentFrame = null;
        } finally {
            connectionLock.unlock();
        }
    }

    private static void closeQuietly(Frame frame) {
        if (frame == null) return;
        try {
            frame.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
