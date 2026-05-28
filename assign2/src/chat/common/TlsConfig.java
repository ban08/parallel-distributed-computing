package chat.common;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;

/** Creates plain TCP sockets or TLS sockets from keystore/truststore settings. */
public final class TlsConfig {
    private final boolean enabled;
    private final Path keyStorePath;
    private final char[] keyStorePassword;
    private final Path trustStorePath;
    private final char[] trustStorePassword;
    private final boolean needClientAuth;

    private TlsConfig(boolean enabled, Path keyStorePath, char[] keyStorePassword,
                      Path trustStorePath, char[] trustStorePassword, boolean needClientAuth) {
        this.enabled = enabled;
        this.keyStorePath = keyStorePath;
        this.keyStorePassword = clonePassword(keyStorePassword);
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = clonePassword(trustStorePassword);
        this.needClientAuth = needClientAuth;
    }

    public static TlsConfig disabled() {
        return new TlsConfig(false, null, null, null, null, false);
    }

    public static TlsConfig server(Path keyStorePath, char[] keyStorePassword,
                                   Path trustStorePath, char[] trustStorePassword,
                                   boolean needClientAuth) {
        Objects.requireNonNull(keyStorePath, "keyStorePath");
        Objects.requireNonNull(keyStorePassword, "keyStorePassword");
        if (needClientAuth && trustStorePath == null) {
            throw new IllegalArgumentException("client-auth TLS requires a truststore");
        }
        if (trustStorePath != null && trustStorePassword == null) {
            throw new IllegalArgumentException("truststore password is required");
        }
        return new TlsConfig(true, keyStorePath, keyStorePassword,
                trustStorePath, trustStorePassword, needClientAuth);
    }

    public static TlsConfig client(Path trustStorePath, char[] trustStorePassword) {
        if (trustStorePath != null && trustStorePassword == null) {
            throw new IllegalArgumentException("truststore password is required");
        }
        return new TlsConfig(true, null, null, trustStorePath, trustStorePassword, false);
    }

    public boolean enabled() {
        return enabled;
    }

    public ServerSocket createServerSocket(int port) throws IOException {
        if (!enabled) return new ServerSocket(port);

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(loadKeyManagers(), loadTrustManagersOrNull(), null);

            SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(port);
            server.setUseClientMode(false);
            server.setNeedClientAuth(needClientAuth);
            return server;
        } catch (GeneralSecurityException e) {
            throw new IOException("could not initialize TLS server: " + e.getMessage(), e);
        }
    }

    public Socket createClientSocket(String host, int port) throws IOException {
        if (!enabled) return new Socket(host, port);

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, loadTrustManagersOrNull(), null);

            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return socket;
        } catch (GeneralSecurityException e) {
            throw new IOException("could not initialize TLS client: " + e.getMessage(), e);
        }
    }

    private KeyManager[] loadKeyManagers() throws IOException, GeneralSecurityException {
        KeyStore keyStore = loadStore(keyStorePath, keyStorePassword);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, keyStorePassword);
        return factory.getKeyManagers();
    }

    private TrustManager[] loadTrustManagersOrNull() throws IOException, GeneralSecurityException {
        if (trustStorePath == null) return null;

        KeyStore trustStore = loadStore(trustStorePath, trustStorePassword);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }

    private static KeyStore loadStore(Path path, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        try (var in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }

    private static char[] clonePassword(char[] password) {
        return password == null ? null : password.clone();
    }
}
