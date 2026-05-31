package chat.server;

import chat.auth.PasswordHasher;
import chat.common.Frame;
import chat.room.Room;
import chat.session.Session;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Manual integration test for C1/C2 over real TCP sockets. */
public final class ConnectionHandlerManualTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chat-server-test-");
        Path users = dir.resolve("users.txt");
        writeUsers(users);
        ServerState state = ServerState.load(users);
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread acceptOne = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));

            String token;
            try (Socket client = new Socket("localhost", port); Frame frame = new Frame(client)) {
                client.setSoTimeout(3000);
                frame.writeLine("NOPE something");
                expect(frame.readLine(), "ERR unknown command");

                frame.writeLine("LOGIN miguel password123");
                String login = frame.readLine();
                if (login == null || !login.startsWith("OK TOKEN ")) {
                    throw new AssertionError("bad login response: " + login);
                }
                token = login.substring("OK TOKEN ".length());
                expect(frame.readLine(), "ROOMS 0");

                frame.writeLine("QUIT");
                expect(frame.readLine(), "BYE");
            }
            acceptOne.join();
            if (state.sessions().size() != 0) throw new AssertionError("QUIT should remove session");

            Thread acceptRejectedResume = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
            try (Socket client = new Socket("localhost", port); Frame frame = new Frame(client)) {
                client.setSoTimeout(3000);
                frame.writeLine("TOKEN " + token);
                expect(frame.readLine(), "ERR invalid token");
                frame.writeLine("QUIT");
                expect(frame.readLine(), "BYE");
            }
            acceptRejectedResume.join();

            String resumableToken;
            Thread acceptInterrupted = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
            Socket interrupted = new Socket("localhost", port);
            try (Frame frame = new Frame(interrupted)) {
                interrupted.setSoTimeout(3000);
                resumableToken = login(frame, "miguel", "password123");
                closeSocket(interrupted);
            }
            acceptInterrupted.join();

            Thread acceptResume = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
            try (Socket client = new Socket("localhost", port); Frame frame = new Frame(client)) {
                client.setSoTimeout(3000);
                frame.writeLine("TOKEN " + resumableToken);
                expect(frame.readLine(), "OK RESUMED miguel");

                Thread acceptTakeover = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));
                try (Socket takeover = new Socket("localhost", port); Frame takeoverFrame = new Frame(takeover)) {
                    takeover.setSoTimeout(3000);
                    takeoverFrame.writeLine("TOKEN " + resumableToken);
                    expect(takeoverFrame.readLine(), "OK RESUMED miguel");
                    expectTransportClosed(frame);

                    takeoverFrame.writeLine("LIST");
                    expect(takeoverFrame.readLine(), "ROOMS 0");

                    takeoverFrame.writeLine("QUIT");
                    expect(takeoverFrame.readLine(), "BYE");
                }
                acceptTakeover.join();
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

                aliceFrame.writeLine("CREATE_AI doodle -- summarize AI availability");
                expect(aliceFrame.readLine(), "OK CREATED_AI doodle");

                aliceFrame.writeLine("CREATE_AI OneWord legacy prompt");
                expect(aliceFrame.readLine(), "ERR usage CREATE_AI <roomName> -- <prompt>");

                aliceFrame.writeLine("CREATE Bad Room");
                expect(aliceFrame.readLine(), "ERR bad room name");

                aliceFrame.writeLine("LIST");
                expect(aliceFrame.readLine(), "ROOMS 2 Library doodle[AI]");

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

                    bobFrame.writeLine("MSG live after resume");
                    expectRoomMessage(bobFrame.readLine(), "bob", "live after resume");
                    expectRoomMessage(aliceResumeFrame.readLine(), "bob", "live after resume");

                    bobFrame.writeLine("LEAVE");
                    expect(bobFrame.readLine(), "LEFT Library");
                    expect(aliceResumeFrame.readLine(), "SYS bob left the room");

                    bobFrame.writeLine("MSG after leave");
                    expect(bobFrame.readLine(), "ERR not in room");

                    bobFrame.writeLine("JOIN AutoRoom");
                    expect(bobFrame.readLine(), "ERR room not found");
                    bobFrame.writeLine("CREATE AutoRoom");
                    expect(bobFrame.readLine(), "OK CREATED AutoRoom");
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

        Room expiryRoom = state.rooms().create("ExpiryRoom");
        Session expiring = state.sessions().create(state.users().get("miguel"), 8, Duration.ofMillis(20));
        expiring.enterRoom(expiryRoom.name());
        expiryRoom.join(expiring);
        Thread.sleep(40);
        Session cleanupTrigger = state.login("miguel", "password123".toCharArray());
        if (cleanupTrigger == null) throw new AssertionError("cleanup trigger login failed");
        var expiryHistory = expiryRoom.recentHistory(10);
        if (!"miguel left the room".equals(expiryHistory.get(expiryHistory.size() - 1).text())) {
            throw new AssertionError("expired session remained in room");
        }
        state.logout(cleanupTrigger);

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

    private static void expectTransportClosed(Frame frame) throws Exception {
        try {
            frame.writeLine("LIST");
            String response = frame.readLine();
            if (response != null) throw new AssertionError("stale transport still responded: " + response);
        } catch (IOException expected) {
            // expected path
        }
    }

    private static void writeUsers(Path path) throws IOException {
        PasswordHasher hasher = new PasswordHasher();
        char[] miguelPassword = "password123".toCharArray();
        char[] bobPassword = "bob123".toCharArray();
        try {
            Files.writeString(path,
                    "miguel:" + hasher.hash(miguelPassword) + "\n"
                    + "bob:" + hasher.hash(bobPassword) + "\n");
        } finally {
            PasswordHasher.clear(miguelPassword);
            PasswordHasher.clear(bobPassword);
        }
    }

    private static String login(Frame frame, String username, String password) throws Exception {
        frame.writeLine("LOGIN " + username + " " + password);
        String response = frame.readLine();
        expectStartsWith(response, "OK TOKEN ");
        expect(frame.readLine(), "ROOMS 0");
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
