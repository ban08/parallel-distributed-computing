package chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line client entry point.
 *
 * The client deliberately has two independent virtual-thread activities:
 * terminal input and network connection management. Terminal input must remain
 * usable while the connection manager blocks in socket reads, reconnects, or
 * waits between retries. {@link ClientState} is the small lock-protected bridge
 * shared by those activities.
 */
public final class ChatClient {

    /** Parses CLI options, starts the client activities, and coordinates shutdown. */
    public static void main(String[] args) {
        ClientOptions options;
        try {
            options = ClientOptions.parse(args);
            if (options.help()) {
                printUsage();
                return;
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[client] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        try (BufferedReader stdin = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            ClientState state = new ClientState();
            ConnectionManager connection = new ConnectionManager(
                    options.host(), options.port(), state, System.out, System.err);
            Thread connectionThread = connection.start();
            Thread input = Thread.ofVirtual()
                    .name("client-input")
                    .start(new ClientInput(connection, stdin, state, System.out, System.err));

            // Either EOF-/quit-driven input shutdown or connection-manager
            // shutdown is enough to stop the remaining client activity.
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

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -cp out chat.client.ChatClient [host] [port]
                """);
    }

    /** Immutable result of translating raw CLI arguments into runtime options. */
    private record ClientOptions(String host, int port, boolean help) {
        static ClientOptions parse(String[] args) {
            List<String> positionals = new ArrayList<>();
            boolean help = false;

            for (String arg : args) {
                switch (arg) {
                    case "--help", "-h" -> help = true;
                    default -> {
                        if (arg.startsWith("--")) throw new IllegalArgumentException("unknown option: " + arg);
                        positionals.add(arg);
                    }
                }
            }

            if (positionals.size() > 2) throw new IllegalArgumentException("too many positional arguments");

            String host = positionals.isEmpty() ? "localhost" : positionals.get(0);
            int port = positionals.size() > 1 ? parsePort(positionals.get(1)) : 8443;

            return new ClientOptions(host, port, help);
        }

        private static int parsePort(String raw) {
            try {
                int port = Integer.parseInt(raw);
                if (port <= 0 || port > 65535) throw new IllegalArgumentException("port out of range");
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid port: " + raw);
            }
        }
    }

    private ChatClient() {}
}
