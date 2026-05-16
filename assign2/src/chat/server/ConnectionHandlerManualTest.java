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
                client.setSoTimeout(3000);
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
                client.setSoTimeout(3000);
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

            Thread acceptAlice = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
            Thread acceptBob = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
            try (
                    Socket alice = new Socket("localhost", port);
                    Socket bob = new Socket("localhost", port);
                    Frame aliceFrame = new Frame(alice);
                    Frame bobFrame = new Frame(bob)
            ) {
                alice.setSoTimeout(3000);
                bob.setSoTimeout(3000);

                String aliceToken = login(aliceFrame, "miguel", "password123");
                login(bobFrame, "bob", "bob123");

                aliceFrame.writeLine("LIST");
                expect(aliceFrame.readLine(), "ROOMS 0");

                aliceFrame.writeLine("CREATE Library");
                expect(aliceFrame.readLine(), "OK CREATED Library");

                aliceFrame.writeLine("CREATE Library");
                expect(aliceFrame.readLine(), "ERR room exists");

                aliceFrame.writeLine("LIST");
                expect(aliceFrame.readLine(), "ROOMS 1 Library");

                aliceFrame.writeLine("JOIN Library");
                expect(aliceFrame.readLine(), "JOINED Library");
                expect(aliceFrame.readLine(), "SYS miguel entered the room");

                bobFrame.writeLine("JOIN Library");
                expect(bobFrame.readLine(), "JOINED Library");
                expect(bobFrame.readLine(), "SYS bob entered the room");
                expect(aliceFrame.readLine(), "SYS bob entered the room");

                aliceFrame.writeLine("MSG hello bob");
                expectRoomMessage(aliceFrame.readLine(), "miguel", "hello bob");
                expectRoomMessage(bobFrame.readLine(), "miguel", "hello bob");

                closeSocket(alice);
                acceptAlice.join();

                bobFrame.writeLine("MSG missed one");
                expectRoomMessage(bobFrame.readLine(), "bob", "missed one");
                bobFrame.writeLine("MSG missed two");
                expectRoomMessage(bobFrame.readLine(), "bob", "missed two");

                Thread acceptAliceResume = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
                try (Socket aliceResume = new Socket("localhost", port);
                     Frame aliceResumeFrame = new Frame(aliceResume)) {
                    aliceResume.setSoTimeout(3000);
                    aliceResumeFrame.writeLine("TOKEN " + aliceToken);
                    expect(aliceResumeFrame.readLine(), "OK RESUMED miguel");
                    expect(aliceResumeFrame.readLine(), "JOINED Library");
                    expect(aliceResumeFrame.readLine(), "HIST 2");
                    expectRoomMessage(aliceResumeFrame.readLine(), "bob", "missed one");
                    expectRoomMessage(aliceResumeFrame.readLine(), "bob", "missed two");

                    bobFrame.writeLine("LEAVE");
                    expect(bobFrame.readLine(), "LEFT Library");
                    expect(aliceResumeFrame.readLine(), "SYS bob left the room");

                    bobFrame.writeLine("MSG after leave");
                    expect(bobFrame.readLine(), "ERR not in room");

                    bobFrame.writeLine("JOIN AutoRoom");
                    expect(bobFrame.readLine(), "JOINED AutoRoom");
                    expect(bobFrame.readLine(), "SYS bob entered the room");

                    bobFrame.writeLine("QUIT");
                    expect(bobFrame.readLine(), "BYE");
                    aliceResumeFrame.writeLine("QUIT");
                    expect(aliceResumeFrame.readLine(), "BYE");
                }
                acceptAliceResume.join();
            }
            acceptBob.join();
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

    private static void closeSocket(Socket socket) throws Exception {
        socket.close();
    }

    private static String login(Frame frame, String username, String password) throws Exception {
        frame.writeLine("LOGIN " + username + " " + password);
        String response = frame.readLine();
        expectStartsWith(response, "OK TOKEN ");
        return response.substring("OK TOKEN ".length());
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void expectStartsWith(String actual, String prefix) {
        if (actual == null || !actual.startsWith(prefix)) {
            throw new AssertionError("expected prefix <" + prefix + "> but got <" + actual + ">");
        }
    }

    private static void expectRoomMessage(String actual, String author, String text) {
        String prefix = "MSG " + author + " ";
        if (actual == null || !actual.startsWith(prefix) || !actual.endsWith(" " + text)) {
            throw new AssertionError("bad room message: " + actual);
        }

        String epoch = actual.substring(prefix.length(), actual.length() - text.length() - 1);
        try {
            Long.parseLong(epoch);
        } catch (NumberFormatException e) {
            throw new AssertionError("bad room message timestamp: " + actual, e);
        }
    }

    private ConnectionHandlerManualTest() {}
}
