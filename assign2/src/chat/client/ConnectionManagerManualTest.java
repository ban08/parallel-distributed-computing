package chat.client;

import chat.common.Frame;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Manual socket check for reconnect + automatic token resume. */
public final class ConnectionManagerManualTest {
    public static void main(String[] args) throws Exception {
        ClientState state = new ClientState();
        state.rememberResumeAttempt("tok-test");
        state.observeServerFrame("OK RESUMED alice");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(output, true, StandardCharsets.UTF_8);

        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            ConnectionManager manager = new ConnectionManager(
                    "localhost",
                    server.getLocalPort(),
                    state,
                    out,
                    err,
                    new ReconnectBackoff(1L, 1L, new Random(1L)));
            Thread managerThread = manager.start();

            try (Socket first = server.accept(); Frame frame = new Frame(first)) {
                expect(frame.readLine(), "TOKEN tok-test");
                frame.writeLine("OK RESUMED alice");
            }

            try (Socket second = server.accept(); Frame frame = new Frame(second)) {
                expect(frame.readLine(), "TOKEN tok-test");
                frame.writeLine("OK RESUMED alice");
                manager.stop();
            }

            managerThread.join(1000);
            if (managerThread.isAlive()) throw new AssertionError("manager did not stop");
        }

        System.out.println("PASS ConnectionManagerManualTest");
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private ConnectionManagerManualTest() {}
}
