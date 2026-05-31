package chat.session;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Secure session capability. Value format is URL-safe Base64 without padding.
 *
 * A token lets a reconnecting client recover server-side session state without
 * caching or resending credentials. Tokens are random, validated, and bounded
 * by an expiry instant.
 */
public record Token(String value, Instant issuedAt, Instant expiresAt) {
    private static final int TOKEN_BYTES = 32;
    private static final Duration TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern TOKEN_VALUE = Pattern.compile("[A-Za-z0-9_-]{32,}");

    public Token {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!TOKEN_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid token format");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    /** Issues a token with the default 24 hour TTL. */
    public static Token issue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        // 32 random bytes give 256 bits of entropy before Base64 encoding.
        RANDOM.nextBytes(bytes);
        Instant now = Instant.now();
        return new Token(ENCODER.encodeToString(bytes), now, now.plus(TTL));
    }

    public boolean isExpired() {
        return !Instant.now().isBefore(expiresAt);
    }

}
