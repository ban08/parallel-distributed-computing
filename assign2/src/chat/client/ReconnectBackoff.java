package chat.client;

import java.util.Random;

/** Exponential backoff with full jitter, capped to avoid reconnect storms. */
final class ReconnectBackoff {
    private final long baseMillis;
    private final long capMillis;
    private final Random random;
    private int attempts;

    ReconnectBackoff() {
        this.baseMillis = 250L;
        this.capMillis = 5000L;
        this.random = new Random();
    }

    long nextDelayMillis() {
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

    void reset() {
        attempts = 0;
    }
}
