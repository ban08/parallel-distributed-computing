package chat.client;

import java.util.Objects;
import java.util.Random;

/** Exponential backoff with full jitter, capped to avoid reconnect storms. */
public final class ReconnectBackoff {
    private final long baseMillis;
    private final long capMillis;
    private final Random random;
    private int attempts;

    public ReconnectBackoff() {
        this(250L, 5000L, new Random());
    }

    ReconnectBackoff(long baseMillis, long capMillis, Random random) {
        if (baseMillis <= 0L) throw new IllegalArgumentException("baseMillis must be positive");
        if (capMillis < baseMillis) throw new IllegalArgumentException("capMillis must be >= baseMillis");
        this.baseMillis = baseMillis;
        this.capMillis = capMillis;
        this.random = Objects.requireNonNull(random, "random");
    }

    public long nextDelayMillis() {
        long max = capMillis;
        if (attempts < 30) {
            // Stop shifting before long overflow can wrap into a negative value.
            long candidate = baseMillis << attempts;
            max = Math.min(capMillis, Math.max(baseMillis, candidate));
        }
        attempts++;
        // Full jitter spreads simultaneous reconnecting clients across the
        // complete interval [0, max], avoiding synchronized retry bursts.
        return random.nextLong(max + 1L);
    }

    public void reset() {
        attempts = 0;
    }
}
