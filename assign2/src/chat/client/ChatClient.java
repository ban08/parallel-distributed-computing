package chat.client;

import chat.common.Frame;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class ChatClient {

    public static void main(String[] args) {
        String host = (args.length > 0) ? args[0] : "localhost";
        int port = (args.length > 1) ? Integer.parseInt(args[1]) : 8443;

        try (Socket socket = new Socket(host, port);
             Frame frame = new Frame(socket);
             BufferedReader stdin = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            Thread.ofVirtual().name("client-reader").start(() -> {
                try {
                    String line;
                    while ((line = frame.readLine()) != null) {
                        System.out.println("< " + line);
                    }
                } catch (IOException ignored) {
                    // socket closed
                }
            });

            String userLine;
            while ((userLine = stdin.readLine()) != null) {
                frame.writeLine(userLine);
            }
        } catch (IOException e) {
            System.err.println("[client] " + e.getMessage());
            System.exit(1);
        }
    }

    private ChatClient() {}
}
