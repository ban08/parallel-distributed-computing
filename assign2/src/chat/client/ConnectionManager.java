package chat.client;

import chat.common.Frame;

import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Owns the current TCP connection and reconnects with token resume. */
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
        this(host, port, state, out, err, new ReconnectBackoff());
    }

    ConnectionManager(String host, int port, ClientState state, PrintStream out, PrintStream err,
                      ReconnectBackoff backoff) {
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port out of range");
        this.port = port;
        this.state = Objects.requireNonNull(state, "state");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    public Thread start() {
        return Thread.ofVirtual().name("client-connection-manager").start(this);
    }

    @Override
    public void run() {
        while (state.isRunning()) {
            try (Socket socket = new Socket(host, port);
                 Frame frame = new Frame(socket)) {
                installFrame(frame);
                backoff.reset();
                out.println("[client] connected to " + host + ":" + port);
                resumeIfPossible();

                new ClientReader(frame, state, out, err, false).run();
            } catch (IOException e) {
                if (state.isRunning()) err.println("[client] connection failed: " + e.getMessage());
            } finally {
                clearFrame();
            }

            if (state.isRunning()) sleepBeforeReconnect();
        }
    }

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
