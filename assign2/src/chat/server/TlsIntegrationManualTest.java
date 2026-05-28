package chat.server;

import chat.common.Frame;
import chat.common.TlsConfig;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Manual integration test for TLS sockets with a generated local keystore. */
public final class TlsIntegrationManualTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chat-tls-test-");
        Path keyStore = dir.resolve("server.p12");
        Path cert = dir.resolve("server.crt");
        Path trustStore = dir.resolve("truststore.p12");
        String password = "changeit";

        createStores(keyStore, cert, trustStore, password);

        Path users = dir.resolve("users.txt");
        ServerState state = ServerState.load(users);
        if (!state.users().register("tlsuser", "password123".toCharArray())) {
            throw new AssertionError("failed to register tls user");
        }

        TlsConfig serverTls = TlsConfig.server(keyStore, password.toCharArray(), null, null, false);
        TlsConfig clientTls = TlsConfig.client(trustStore, password.toCharArray());

        try (ServerSocket server = serverTls.createServerSocket(0)) {
            if (!(server instanceof SSLServerSocket)) {
                throw new AssertionError("server socket is not TLS");
            }

            Thread acceptOne = Thread.ofVirtual().start(() -> acceptAndHandle(server, state));

            try (Socket client = clientTls.createClientSocket("localhost", server.getLocalPort());
                 Frame frame = new Frame(client)) {
                if (!(client instanceof SSLSocket)) {
                    throw new AssertionError("client socket is not TLS");
                }
                client.setSoTimeout(3000);

                frame.writeLine("LOGIN tlsuser password123");
                expectStartsWith(frame.readLine(), "OK TOKEN ");
                expect(frame.readLine(), "ROOMS 0");

                frame.writeLine("QUIT");
                expect(frame.readLine(), "BYE");
            }

            acceptOne.join(5000);
            if (acceptOne.isAlive()) throw new AssertionError("TLS handler did not stop");
        }

        System.out.println("PASS TlsIntegrationManualTest");
    }

    private static void createStores(Path keyStore, Path cert, Path trustStore, String password) throws Exception {
        runKeytool(
                "-genkeypair",
                "-alias", "chat",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-keystore", keyStore.toString(),
                "-storepass", password,
                "-keypass", password,
                "-dname", "CN=localhost",
                "-storetype", "PKCS12",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1");
        runKeytool(
                "-exportcert",
                "-alias", "chat",
                "-keystore", keyStore.toString(),
                "-storepass", password,
                "-rfc",
                "-file", cert.toString());
        runKeytool(
                "-importcert",
                "-alias", "chat",
                "-file", cert.toString(),
                "-keystore", trustStore.toString(),
                "-storepass", password,
                "-storetype", "PKCS12",
                "-noprompt");
    }

    private static void runKeytool(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = keytool();
        System.arraycopy(args, 0, command, 1, args.length);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("keytool failed: "
                    + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static String keytool() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "keytool.exe"
                : "keytool";
        Path bundled = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.exists(bundled) ? bundled.toString() : executable;
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

    private static void expectStartsWith(String actual, String prefix) {
        if (actual == null || !actual.startsWith(prefix)) {
            throw new AssertionError("expected prefix <" + prefix + "> but got <" + actual + ">");
        }
    }

    private TlsIntegrationManualTest() {}
}
