package chat.concurrent.stress;

import chat.concurrent.BoundedQueue;

/**
 * 100 producers × 1000 distinct longs each, 100 consumers, capacity 16.
 * Producer p emits values [p*M, p*M+M). Sum of consumer-side sums must
 * equal n*(n-1)/2 where n = PRODUCERS*PER_PRODUCER. Mismatch ⇒ items lost
 * or duplicated. Hang ⇒ deadlock or lost wake-up (caught by external
 * timeout in the runner).
 *
 * No CountDownLatch / AtomicLong: producers retry the same non-blocking offer
 * used by room broadcasts, then are joined before poison pills are inserted.
 */
public final class BoundedQueueStress {

    private static final int PRODUCERS = 100;
    private static final int CONSUMERS = 100;
    private static final int PER_PRODUCER = 1_000;
    private static final int CAPACITY = 16;
    private static final long POISON = -1L;

    public static void main(String[] args) throws Exception {
        BoundedQueue<Long> q = new BoundedQueue<>(CAPACITY);

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final int pid = p;
            producers[p] = Thread.ofVirtual().name("prod-" + p).start(() -> {
                long base = (long) pid * PER_PRODUCER;
                for (int i = 0; i < PER_PRODUCER; i++) {
                    while (!q.offer(base + i)) {
                        Thread.yield();
                    }
                }
            });
        }

        long[] sums = new long[CONSUMERS];
        Thread[] consumers = new Thread[CONSUMERS];
        for (int c = 0; c < CONSUMERS; c++) {
            final int cid = c;
            consumers[c] = Thread.ofVirtual().name("cons-" + c).start(() -> {
                long s = 0;
                try {
                    while (true) {
                        long v = q.take();
                        if (v == POISON) break;
                        s += v;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                sums[cid] = s;
            });
        }

        for (Thread t : producers) t.join();
        for (int i = 0; i < CONSUMERS; i++) {
            while (!q.offer(POISON)) Thread.yield();
        }
        for (Thread t : consumers) t.join();

        long total = 0;
        for (long s : sums) total += s;
        long n = (long) PRODUCERS * PER_PRODUCER;
        long expected = n * (n - 1) / 2;

        if (total != expected) {
            System.err.println("FAIL BoundedQueueStress: expected sum " + expected + ", got " + total);
            System.exit(1);
        }
        if (q.size() != 0) {
            System.err.println("FAIL BoundedQueueStress: queue not drained, size=" + q.size());
            System.exit(1);
        }
        System.out.println("PASS BoundedQueueStress: " + n + " items, sum=" + total);
    }

    private BoundedQueueStress() {}
}
