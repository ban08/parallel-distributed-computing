package chat.client;

import chat.common.Frame;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class ChatClient {

    public static void main(String[] args) {
        String host = (args.length > 0) ? args[0] : "localhost";
        int port = (args.length > 1) ? Integer.parseInt(args[1]) : 8443;

        try (Socket socket = new Socket(host, port);
             Frame frame = new Frame(socket);
             BufferedReader stdin = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            ClientState state = new ClientState();
            Thread reader = Thread.ofVirtual()
                    .name("client-reader")
                    .start(new ClientReader(frame, state, System.out, System.err));
            Thread input = Thread.ofVirtual()
                    .name("client-input")
                    .start(new ClientInput(frame, stdin, state, System.out, System.err));

            waitUntilOneStops(reader, input);
            if (!input.isAlive() && reader.isAlive()) joinQuietly(reader, 1000L);
            state.stop();
            closeQuietly(frame);
            reader.interrupt();
            input.interrupt();
            joinQuietly(reader, 1000L);
        } catch (IOException | InterruptedException e) {
            System.err.println("[client] " + e.getMessage());
            System.exit(1);
        }
    }

    private static void waitUntilOneStops(Thread reader, Thread input) throws InterruptedException {
        while (reader.isAlive() && input.isAlive()) {
            reader.join(250L);
            input.join(250L);
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Frame frame) {
        try {
            frame.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private ChatClient() {}
}
