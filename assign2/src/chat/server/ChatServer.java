package chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server process entry point and accept loop.
 *
 * One shared {@link ServerState} is created at startup. Every accepted socket
 * receives its own virtual-thread {@link ConnectionHandler}; logical users,
 * sessions, and rooms therefore remain shared across transports.
 */
public final class ChatServer {

    public static void main(String[] args) {
        ServerOptions options;
        try {
            options = ServerOptions.parse(args);
            if (options.help()) {
                printUsage();
                return;
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[server] " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        try {
            ServerState state = ServerState.load(options.usersPath(), options.ollamaUrl(), options.ollamaModel());
            serve(options.port(), state);
        } catch (IOException e) {
            System.err.println("[server] fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void serve(int port, ServerState state) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[server] listening on port " + server.getLocalPort());
            System.out.println("[server] users file: " + state.usersPath());
            System.out.println("[server] loaded users: " + state.users().size());
            System.out.println("[server] Ollama endpoint: " + state.ollamaClient().baseUrl()
                    + " (model: " + state.ollamaClient().model() + ")");

            while (true) {
                Socket socket = server.accept();
                try {
                    // OS-level keepalive eventually detects dead peers even
                    // when no application frame is currently in flight.
                    socket.setKeepAlive(true);
                } catch (IOException e) {
                    try {
                        socket.close();
                    } catch (IOException closeError) {
                        e.addSuppressed(closeError);
                    }
                    System.err.println("[server] rejected socket without TCP keepalive: " + e.getMessage());
                    continue;
                }
                Thread.ofVirtual()
                        .name("conn-" + socket.getRemoteSocketAddress())
                        .start(new ConnectionHandler(state, socket));
            }
        }
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -cp out chat.server.ChatServer [port] [usersFile] [ollamaUrl] [ollamaModel]
                """);
    }

    private record ServerOptions(int port, Path usersPath, String ollamaUrl, String ollamaModel,
                                 boolean help) {
        static ServerOptions parse(String[] args) {
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

            if (positionals.size() > 4) throw new IllegalArgumentException("too many positional arguments");

            int port = positionals.isEmpty() ? 8443 : parsePort(positionals.get(0));
            Path usersPath = positionals.size() > 1 ? Path.of(positionals.get(1)) : ServerState.DEFAULT_USERS_FILE;
            String ollamaUrl = positionals.size() > 2 ? positionals.get(2) : null;
            String ollamaModel = positionals.size() > 3 ? positionals.get(3) : null;

            return new ServerOptions(port, usersPath, ollamaUrl, ollamaModel, help);
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

    private ChatServer() {}
}
