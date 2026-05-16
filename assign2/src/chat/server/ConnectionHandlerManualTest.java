package chat.server;

import chat.common.Frame;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/** Manual integration test for C1/C2 over real TCP sockets. */
public final class ConnectionHandlerManualTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chat-server-test-");
        Path users = dir.resolve("users.txt");
        ServerState state = ServerState.load(users);
        if (!state.users().register("miguel", "password123".toCharArray())) {
            throw new AssertionError("failed to register test user");
        }

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptOne = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));

            String token;
            try (Socket client = new Socket("localhost", port); Frame frame = new Frame(client)) {
                frame.writeLine("PING");
                expect(frame.readLine(), "PONG");

                frame.writeLine("NOPE something");
                expect(frame.readLine(), "ERR unknown command");

                frame.writeLine("LOGIN miguel password123");
                String login = frame.readLine();
                if (login == null || !login.startsWith("OK TOKEN ")) {
                    throw new AssertionError("bad login response: " + login);
                }
                token = login.substring("OK TOKEN ".length());

                frame.writeLine("PING");
                expect(frame.readLine(), "PONG");

                frame.writeLine("WHOAMI");
                expect(frame.readLine(), "OK USER miguel");

                frame.writeLine("QUIT");
                expect(frame.readLine(), "BYE");
            }
            acceptOne.join();

            Thread acceptResume = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
            try (Socket client = new Socket("localhost", port); Frame frame = new Frame(client)) {
                frame.writeLine("TOKEN " + token);
                expect(frame.readLine(), "OK RESUMED miguel");

                frame.writeLine("PING");
                expect(frame.readLine(), "PONG");

                frame.writeLine("WHOAMI");
                expect(frame.readLine(), "OK USER miguel");

                frame.writeLine("QUIT");
                expect(frame.readLine(), "BYE");
            }
            acceptResume.join();
        }

        System.out.println("PASS ConnectionHandlerManualTest");
    }

    private static void acceptAndHandle(ServerSocket server, ServerState state) {
        try {
            Socket socket = server.accept();
            new ConnectionHandler(state, socket).run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private ConnectionHandlerManualTest() {}
}
