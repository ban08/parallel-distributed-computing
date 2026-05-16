package chat.session;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** Secure session token. Value format is URL-safe Base64 without padding. */
public record Token(String value, Instant issuedAt, Instant expiresAt) {
    private static final int TOKEN_BYTES = 32;
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
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

    /** Issues a token with the default TTL. */
    public static Token issue() {
        return issue(DEFAULT_TTL);
    }

    /** Issues a token with the supplied TTL. */
    public static Token issue(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        Instant now = Instant.now();
        return new Token(ENCODER.encodeToString(bytes), now, now.plus(ttl));
    }

    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(expiresAt);
    }

    public Duration remainingTtl() {
        return remainingTtl(Instant.now());
    }

    public Duration remainingTtl(Instant now) {
        Objects.requireNonNull(now, "now");
        if (isExpired(now)) return Duration.ZERO;
        return Duration.between(now, expiresAt);
    }
}
