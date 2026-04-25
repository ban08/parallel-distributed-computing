package chat.concurrent.stress;

import chat.concurrent.BoundedQueue;

import java.util.concurrent.TimeUnit;

/**
 * Verifies that timed offer/poll honour their deadlines: a 100 ms timeout
 * must elapse roughly 100 ms (lower bound rules out broken timer; upper
 * bound rules out await(timeout) restart-on-spurious bug).
 */
public final class BoundedQueueTimeoutStress {

    private static final long TIMEOUT_MS = 100L;
    private static final long LOWER_MS = 90L;
    private static final long UPPER_MS = 300L;

    public static void main(String[] args) throws Exception {
        BoundedQueue<Integer> full = new BoundedQueue<>(2);
        full.put(1);
        full.put(2);
        long t0 = System.nanoTime();
        boolean inserted = full.offer(3, TIMEOUT_MS, TimeUnit.MILLISECONDS);
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        if (inserted) {
            System.err.println("FAIL: offer should have timed out");
            System.exit(1);
        }
        if (elapsed < LOWER_MS || elapsed > UPPER_MS) {
            System.err.println("FAIL: offer timeout took " + elapsed + " ms, expected ~" + TIMEOUT_MS);
            System.exit(1);
        }

        BoundedQueue<Integer> empty = new BoundedQueue<>(2);
        t0 = System.nanoTime();
        Integer v = empty.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        elapsed = (System.nanoTime() - t0) / 1_000_000;
        if (v != null) {
            System.err.println("FAIL: poll should have timed out");
            System.exit(1);
        }
        if (elapsed < LOWER_MS || elapsed > UPPER_MS) {
            System.err.println("FAIL: poll timeout took " + elapsed + " ms, expected ~" + TIMEOUT_MS);
            System.exit(1);
        }

        System.out.println("PASS BoundedQueueTimeoutStress");
    }

    private BoundedQueueTimeoutStress() {}
}
