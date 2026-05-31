package chat.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2 password hashing.
 *
 * Stored format: {@code pbkdf2$iterations$saltBase64$hashBase64}. The encoded
 * value contains everything needed for verification but never contains the
 * original password. A fresh random salt prevents equal passwords from
 * producing equal stored strings.
 */
public final class PasswordHasher {
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    /** Hashes a new password with a fresh random salt. */
    public String hash(char[] password) {
        requirePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] digest = pbkdf2(password, salt, ITERATIONS, HASH_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(digest);
    }

    /** Recomputes the encoded PBKDF2 digest and compares it in constant time. */
    public boolean verify(char[] password, String storedHash) {
        requirePassword(password);
        Objects.requireNonNull(storedHash, "storedHash");

        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;

        try {
            // Read parameters from the stored value so hashes remain verifiable
            // if the configured work factor changes in a future version.
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations, expected.length * 8);
            return constantTimeEquals(actual, expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Best-effort removal of plaintext password characters after use. */
    public static void clear(char[] password) {
        if (password != null) Arrays.fill(password, '\0');
    }

    private static void requirePassword(char[] password) {
        Objects.requireNonNull(password, "password");
        if (password.length == 0) throw new IllegalArgumentException("password cannot be empty");
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            KeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("password hashing unavailable", e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        // Do not return on the first mismatch: runtime must not reveal how many
        // prefix bytes were correct.
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
