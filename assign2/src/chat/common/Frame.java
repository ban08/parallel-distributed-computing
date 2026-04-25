package chat.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * UTF-8, newline-delimited frame I/O over a Socket.
 * Reading and writing from different threads on the same Frame is safe
 * (different underlying streams). Concurrent writes from multiple threads
 * are NOT safe — funnel writes through a single thread.
 */
public final class Frame implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    public Frame(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Reads the next frame, or null on clean EOF. */
    public String readLine() throws IOException {
        return in.readLine();
    }

    /** Writes a frame terminated by '\n' and flushes. Frame must not contain '\n' or '\r'. */
    public void writeLine(String line) throws IOException {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("frame must not contain newline characters");
        }
        out.write(line);
        out.write('\n');
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
