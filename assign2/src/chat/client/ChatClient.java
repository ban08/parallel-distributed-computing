package chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ChatClient {

    public static void main(String[] args) {
        String host = (args.length > 0) ? args[0] : "localhost";
        int port = (args.length > 1) ? Integer.parseInt(args[1]) : 8443;

        try (BufferedReader stdin = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            ClientState state = new ClientState();
            ConnectionManager connection = new ConnectionManager(host, port, state, System.out, System.err);
            Thread connectionThread = connection.start();
            Thread input = Thread.ofVirtual()
                    .name("client-input")
                    .start(new ClientInput(connection, stdin, state, System.out, System.err));

            waitUntilOneStops(connectionThread, input);
            connection.stop();
            input.interrupt();
            joinQuietly(connectionThread, 1000L);
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

    private ChatClient() {}
}
