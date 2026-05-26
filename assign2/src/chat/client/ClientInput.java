package chat.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Objects;

/** Reads user-friendly slash commands and sends protocol frames. */
public final class ClientInput implements Runnable {
    private final ConnectionManager connection;
    private final BufferedReader input;
    private final ClientState state;
    private final PrintStream out;
    private final PrintStream err;

    public ClientInput(ConnectionManager connection, BufferedReader input, ClientState state,
                       PrintStream out, PrintStream err) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.input = Objects.requireNonNull(input, "input");
        this.state = Objects.requireNonNull(state, "state");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public void run() {
        out.println("[client] connected. Type /help for commands.");
        try {
            while (state.isRunning()) {
                out.print("> ");
                out.flush();

                String line = input.readLine();
                if (line == null) break;

                ClientCommand command = parseUserLine(line, state);
                if (command.localMessage() != null) out.println(command.localMessage());
                if (command.protocolLine() == null) continue;

                if (command.loginUsername() != null) state.rememberLoginAttempt(command.loginUsername());
                if (command.resumeToken() != null) state.rememberResumeAttempt(command.resumeToken());
                if (!connection.send(command.protocolLine())) {
                    out.println("[client] not connected; command was not sent.");
                    continue;
                }
                if (command.stopAfterSend()) {
                    return;
                }
            }
        } catch (IOException e) {
            if (state.isRunning()) err.println("[client] input stopped: " + e.getMessage());
        }
    }

    static ClientCommand parseUserLine(String line, ClientState state) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(state, "state");

        String trimmed = line.trim();
        if (trimmed.isEmpty()) return ClientCommand.local(null);

        if (!trimmed.startsWith("/")) {
            if (state.currentRoom() == null) {
                return ClientCommand.local("[client] join a room first, or use /msg <text>.");
            }
            return ClientCommand.send("MSG " + trimmed);
        }

        String[] parts = trimmed.split("\\s+", 2);
        String command = parts[0].substring(1).toLowerCase(Locale.ROOT);
        String tail = (parts.length == 2) ? parts[1].trim() : "";

        return switch (command) {
            case "help" -> ClientCommand.local(helpText());
            case "login" -> loginCommand(tail);
            case "resume", "token" -> resumeCommand(tail);
            case "list" -> noTail("LIST", tail, "usage: /list");
            case "create" -> requiredTail("CREATE", tail, "usage: /create <room>");
            case "create_ai" -> createAICommand(tail);
            case "join" -> requiredTail("JOIN", tail, "usage: /join <room>");
            case "msg" -> requiredTail("MSG", tail, "usage: /msg <text>");
            case "leave" -> noTail("LEAVE", tail, "usage: /leave");
            case "ping" -> noTail("PING", tail, "usage: /ping");
            case "whoami" -> noTail("WHOAMI", tail, "usage: /whoami");
            case "quit" -> quitCommand(tail);
            default -> ClientCommand.local("[client] unknown command /" + command + ". Type /help.");
        };
    }

    private static ClientCommand loginCommand(String tail) {
        String[] args = tail.split("\\s+", 2);
        if (tail.isBlank() || args.length != 2 || args[1].isBlank()) {
            return ClientCommand.local("usage: /login <username> <password>");
        }
        return new ClientCommand("LOGIN " + args[0] + " " + args[1], null, false, args[0], null);
    }

    private static ClientCommand resumeCommand(String tail) {
        if (tail.isBlank()) return ClientCommand.local("usage: /resume <token>");
        return new ClientCommand("TOKEN " + tail, null, false, null, tail);
    }

    private static ClientCommand createAICommand(String tail) {
        // Format: /create_ai <roomName> <prompt>
        String[] args = tail.split("\\s+", 2);
        if (tail.isBlank() || args.length < 2 || args[1].isBlank()) {
            return ClientCommand.local("usage: /create_ai <room> <prompt>");
        }
        return ClientCommand.send("CREATE_AI " + args[0] + " " + args[1]);
    }

    private static ClientCommand requiredTail(String protocolCommand, String tail, String usage) {
        if (tail.isBlank()) return ClientCommand.local(usage);
        return ClientCommand.send(protocolCommand + " " + tail);
    }

    private static ClientCommand noTail(String protocolCommand, String tail, String usage) {
        if (!tail.isBlank()) return ClientCommand.local(usage);
        return ClientCommand.send(protocolCommand);
    }

    private static ClientCommand quitCommand(String tail) {
        if (!tail.isBlank()) return ClientCommand.local("usage: /quit");
        return new ClientCommand("QUIT", null, true, null, null);
    }

    private static String helpText() {
        return """
                commands:
                  /login <username> <password>
                  /resume <token>
                  /list
                  /create <room>
                  /create_ai <room> <prompt>
                  /join <room>
                  /msg <text>
                  /leave
                  /whoami
                  /ping
                  /quit
                after joining a room, plain text is sent as a message""";
    }

    record ClientCommand(String protocolLine, String localMessage, boolean stopAfterSend,
                         String loginUsername, String resumeToken) {
        static ClientCommand send(String protocolLine) {
            return new ClientCommand(protocolLine, null, false, null, null);
        }

        static ClientCommand local(String localMessage) {
            return new ClientCommand(null, localMessage, false, null, null);
        }
    }
}
