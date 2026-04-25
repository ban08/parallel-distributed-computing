package chat.server;

import chat.common.Frame;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public final class ChatServer {

    public static void main(String[] args) {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8443;
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("[server] listening on port " + port);
            while (true) {
                Socket s = server.accept();
                Thread.ofVirtual()
                        .name("conn-" + s.getRemoteSocketAddress())
                        .start(() -> handle(s));
            }
        } catch (IOException e) {
            System.err.println("[server] fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void handle(Socket s) {
        var peer = s.getRemoteSocketAddress();
        System.out.println("[server] connected: " + peer);
        try (Frame f = new Frame(s)) {
            String line;
            while ((line = f.readLine()) != null) {
                f.writeLine("ECHO " + line);
            }
        } catch (IOException ignored) {
            // peer closed or network error — fine for sprint 0
        } finally {
            System.out.println("[server] disconnected: " + peer);
        }
    }

    private ChatServer() {}
}
