package chat.client;

/** Manual checks for friendly client command parsing and server frame formatting. */
public final class ClientCommandManualTest {
    public static void main(String[] args) {
        ClientState state = new ClientState();

        ClientInput.ClientCommand login = ClientInput.parseUserLine("/login alice alice123", state);
        expect(login.protocolLine(), "LOGIN alice alice123");
        expect(login.loginUsername(), "alice");
        state.rememberLoginAttempt(login.loginUsername());
        state.observeServerFrame("OK TOKEN tok_123");
        expect(state.username(), "alice");
        expect(state.token(), "tok_123");

        expect(ClientInput.parseUserLine("/list", state).protocolLine(), "LIST");
        expect(ClientInput.parseUserLine("/create Library", state).protocolLine(), "CREATE Library");
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
        expect(ClientReader.formatServerFrame("SYS bob entered the room"), "* bob entered the room");
        String formattedMessage = ClientReader.formatServerFrame("MSG alice 0 hello");
        if (!formattedMessage.startsWith("[") || !formattedMessage.endsWith("] alice: hello")) {
            throw new AssertionError("bad formatted message: " + formattedMessage);
        }
        expect(ClientReader.formatServerFrame("ERR not authenticated"), "[error] not authenticated");

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
