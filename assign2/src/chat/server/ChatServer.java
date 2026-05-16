package chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;

public final class ChatServer {

    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8443;
        Path usersPath = (args.length > 1) ? Path.of(args[1]) : ServerState.DEFAULT_USERS_FILE;

        try {
            ServerState state = ServerState.load(usersPath);
            serve(port, state);
        } catch (IOException e) {
            System.err.println("[server] fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void serve(int port, ServerState state) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[server] listening on port " + port);
            System.out.println("[server] users file: " + state.usersPath());
            System.out.println("[server] loaded users: " + state.users().size());

            while (true) {
                Socket socket = server.accept();
                Thread.ofVirtual()
                        .name("conn-" + socket.getRemoteSocketAddress())
                        .start(new ConnectionHandler(state, socket));
            }
        }
    }

    private ChatServer() {}
}
