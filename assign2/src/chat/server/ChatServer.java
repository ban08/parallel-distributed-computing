package chat.server;

import chat.common.TlsConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
            serve(options.port(), state, options.tlsConfig());
        } catch (IOException e) {
            System.err.println("[server] fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void serve(int port, ServerState state) throws IOException {
        serve(port, state, TlsConfig.disabled());
    }

    public static void serve(int port, ServerState state, TlsConfig tlsConfig) throws IOException {
        try (ServerSocket server = tlsConfig.createServerSocket(port)) {
            System.out.println("[server] listening on port " + server.getLocalPort()
                    + (tlsConfig.enabled() ? " over TLS" : ""));
            System.out.println("[server] users file: " + state.usersPath());
            System.out.println("[server] loaded users: " + state.users().size());
            if (state.ollamaClient() != null) {
                System.out.println("[server] Ollama endpoint: " + state.ollamaClient().baseUrl()
                        + " (model: " + state.ollamaClient().model() + ")");
            } else {
                System.out.println("[server] Ollama: not configured (AI rooms disabled)");
            }

            while (true) {
                Socket socket = server.accept();
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
                  java -cp out chat.server.ChatServer [port] [usersFile] --tls --keystore <path> --keystore-pass <password>

                TLS options:
                  --tls
                  --keystore <path> --keystore-pass <password>
                  --truststore <path> --truststore-pass <password>
                  --tls-client-auth
                """);
    }

    private record ServerOptions(int port, Path usersPath, String ollamaUrl, String ollamaModel,
                                 TlsConfig tlsConfig, boolean help) {
        static ServerOptions parse(String[] args) {
            List<String> positionals = new ArrayList<>();
            boolean tls = false;
            boolean clientAuth = false;
            boolean help = false;
            Path keyStore = null;
            String keyStorePass = null;
            Path trustStore = null;
            String trustStorePass = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> help = true;
                    case "--tls" -> tls = true;
                    case "--keystore" -> keyStore = Path.of(requireValue(args, ++i, arg));
                    case "--keystore-pass" -> keyStorePass = requireValue(args, ++i, arg);
                    case "--truststore" -> trustStore = Path.of(requireValue(args, ++i, arg));
                    case "--truststore-pass" -> trustStorePass = requireValue(args, ++i, arg);
                    case "--tls-client-auth" -> clientAuth = true;
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

            if (!tls && (keyStore != null || keyStorePass != null || trustStore != null
                    || trustStorePass != null || clientAuth)) {
                throw new IllegalArgumentException("TLS options require --tls");
            }
            if (tls && (keyStore == null || keyStorePass == null)) {
                throw new IllegalArgumentException("--tls requires --keystore and --keystore-pass");
            }
            if (trustStore == null && trustStorePass != null) {
                throw new IllegalArgumentException("--truststore-pass requires --truststore");
            }

            TlsConfig tlsConfig = tls
                    ? TlsConfig.server(
                            keyStore,
                            keyStorePass.toCharArray(),
                            trustStore,
                            trustStorePass == null ? null : trustStorePass.toCharArray(),
                            clientAuth)
                    : TlsConfig.disabled();
            return new ServerOptions(port, usersPath, ollamaUrl, ollamaModel, tlsConfig, help);
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

    private ChatServer() {}
}
