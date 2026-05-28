package chat.client;

import chat.common.TlsConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ChatClient {

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
                    options.host(), options.port(), state, System.out, System.err,
                    new ReconnectBackoff(), options.tlsConfig());
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

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -cp out chat.client.ChatClient [host] [port]
                  java -cp out chat.client.ChatClient [host] [port] --tls --truststore <path> --truststore-pass <password>

                --tls may also use the JVM default trust anchors when no truststore is given.""");
    }

    private record ClientOptions(String host, int port, TlsConfig tlsConfig, boolean help) {
        static ClientOptions parse(String[] args) {
            List<String> positionals = new ArrayList<>();
            boolean tls = false;
            boolean help = false;
            Path trustStore = null;
            String trustStorePass = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> help = true;
                    case "--tls" -> tls = true;
                    case "--truststore" -> trustStore = Path.of(requireValue(args, ++i, arg));
                    case "--truststore-pass" -> trustStorePass = requireValue(args, ++i, arg);
                    default -> {
                        if (arg.startsWith("--")) throw new IllegalArgumentException("unknown option: " + arg);
                        positionals.add(arg);
                    }
                }
            }

            if (positionals.size() > 2) throw new IllegalArgumentException("too many positional arguments");

            String host = positionals.isEmpty() ? "localhost" : positionals.get(0);
            int port = positionals.size() > 1 ? parsePort(positionals.get(1)) : 8443;

            if (!tls && (trustStore != null || trustStorePass != null)) {
                throw new IllegalArgumentException("truststore options require --tls");
            }
            if (trustStore == null && trustStorePass != null) {
                throw new IllegalArgumentException("--truststore-pass requires --truststore");
            }

            TlsConfig tlsConfig = tls
                    ? TlsConfig.client(trustStore, trustStorePass == null ? null : trustStorePass.toCharArray())
                    : TlsConfig.disabled();
            return new ClientOptions(host, port, tlsConfig, help);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
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
