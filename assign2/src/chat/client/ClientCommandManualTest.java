package chat.client;

import java.util.Random;

/** Manual checks for friendly client command parsing and server frame formatting. */
public final class ClientCommandManualTest {
    public static void main(String[] args) {
        ClientState state = new ClientState();

        expect(ClientInput.parseUserLine("/register alice alice123", state).protocolLine(), "REGISTER alice alice123");

        ClientInput.ClientCommand login = ClientInput.parseUserLine("/login alice alice123", state);
        expect(login.protocolLine(), "LOGIN alice alice123");
        expect(login.loginUsername(), "alice");
        state.rememberLoginAttempt(login.loginUsername());
        state.observeServerFrame("OK TOKEN tok_123");
        expect(state.username(), "alice");
        expect(state.token(), "tok_123");

        ClientInput.ClientCommand resume = ClientInput.parseUserLine("/resume tok_456", state);
        expect(resume.protocolLine(), "TOKEN tok_456");
        expect(resume.resumeToken(), "tok_456");
        state.rememberResumeAttempt(resume.resumeToken());
        state.observeServerFrame("OK RESUMED alice");
        expect(state.token(), "tok_456");

        expect(ClientInput.parseUserLine("/list", state).protocolLine(), "LIST");
        expect(ClientInput.parseUserLine("/create Library", state).protocolLine(), "CREATE Library");
        expect(ClientInput.parseUserLine("/create AI doodle AI summarize availability", state).protocolLine(),
                "CREATE AI doodle AI summarize availability");
        expect(ClientInput.parseUserLine("/create_ai AI doodle -- summarize availability", state).protocolLine(),
                "CREATE_AI AI doodle -- summarize availability");
        expect(ClientInput.parseUserLine("/create_ai BotRoom summarize availability", state).protocolLine(),
                "CREATE_AI BotRoom summarize availability");
        expect(ClientInput.parseUserLine("/join Library", state).protocolLine(), "JOIN Library");

        state.observeServerFrame("JOINED Library");
        expect(state.currentRoom(), "Library");
        expect(ClientInput.parseUserLine("hello room", state).protocolLine(), "MSG hello room");
        expect(ClientInput.parseUserLine("/msg hello again", state).protocolLine(), "MSG hello again");

        state.observeServerFrame("LEFT Library");
        if (state.currentRoom() != null) throw new AssertionError("room should clear after LEFT");
        if (ClientInput.parseUserLine("hello nowhere", state).protocolLine() != null) {
            throw new AssertionError("plain text outside a room should not be sent");
        }

        ClientInput.ClientCommand quit = ClientInput.parseUserLine("/quit", state);
        expect(quit.protocolLine(), "QUIT");
        if (!quit.stopAfterSend()) throw new AssertionError("quit should stop input after send");

        expect(ClientReader.formatServerFrame("ROOMS 0"), "[rooms] none");
        expect(ClientReader.formatServerFrame("ROOMS 2 Library Games"), "[rooms] Library Games");
        expect(ClientReader.formatServerFrame("HIST 2"), "[history] replaying 2 frame(s)");
        expect(ClientReader.formatServerFrame("SYS bob entered the room"), "* bob entered the room");
        expect(ClientReader.formatServerFrame("OK REGISTERED alice"), "[auth] registered alice");
        String formattedMessage = ClientReader.formatServerFrame("MSG alice 0 hello");
        if (!formattedMessage.startsWith("[") || !formattedMessage.endsWith("] alice: hello")) {
            throw new AssertionError("bad formatted message: " + formattedMessage);
        }
        expect(ClientReader.formatServerFrame("ERR not authenticated"), "[error] not authenticated");

        ReconnectBackoff backoff = new ReconnectBackoff(10L, 50L, new Random(1L));
        for (int i = 0; i < 10; i++) {
            long delay = backoff.nextDelayMillis();
            if (delay < 0L || delay > 50L) throw new AssertionError("backoff out of range: " + delay);
        }

        state.rememberResumeAttempt("bad");
        state.observeServerFrame("ERR invalid token");
        if (state.token() != null || state.currentRoom() != null) {
            throw new AssertionError("invalid token should clear resumable state");
        }

        state.observeServerFrame("BYE");
        if (state.isRunning()) throw new AssertionError("BYE should stop client state");

        System.out.println("PASS ClientCommandManualTest");
    }

    private static void expect(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private ClientCommandManualTest() {}
}
