package chat.concurrent.stress;

import chat.concurrent.LockedMap;

import java.util.Random;

/**
 * 50 readers + 5 writers soak. Default 5 s; pass seconds in args[0].
 *
 * Invariant: any value in the map for key k is exactly k*k. Writers always
 * insert that pair (or remove). A reader observing v != k*k would prove a
 * torn or stale read — should never happen with the LockedMap.
 *
 * No exceptions allowed (in particular no ConcurrentModificationException
 * from snapshot/forEach), and the final map must respect the invariant.
 */
public final class LockedMapStress {

    private static final int READERS = 50;
    private static final int WRITERS = 5;
    private static final int KEY_SPACE = 1024;

    public static void main(String[] args) throws Exception {
        long durationMs = (args.length > 0 ? Long.parseLong(args[0]) : 5L) * 1000L;
        long deadline = System.currentTimeMillis() + durationMs;

        LockedMap<Integer, Long> map = new LockedMap<>();
        Throwable[] failure = new Throwable[1];

        Thread[] all = new Thread[READERS + WRITERS];
        int idx = 0;
        for (int r = 0; r < READERS; r++) {
            final int seed = r;
            all[idx++] = Thread.ofVirtual().name("rd-" + r).start(() -> {
                Random rnd = new Random(seed);
                try {
                    while (System.currentTimeMillis() < deadline) {
                        int key = rnd.nextInt(KEY_SPACE);
                        Long v = map.get(key);
                        if (v != null && v != (long) key * key) {
                            throw new AssertionError("torn read k=" + key + " v=" + v);
                        }
                        if (rnd.nextInt(100) == 0) map.snapshot();
                    }
                } catch (Throwable t) { failure[0] = t; }
            });
        }
        for (int w = 0; w < WRITERS; w++) {
            final int seed = 1000 + w;
            all[idx++] = Thread.ofVirtual().name("wr-" + w).start(() -> {
                Random rnd = new Random(seed);
                try {
                    while (System.currentTimeMillis() < deadline) {
                        int key = rnd.nextInt(KEY_SPACE);
                        if (rnd.nextInt(2) == 0) map.put(key, (long) key * key);
                        else map.remove(key);
                    }
                } catch (Throwable t) { failure[0] = t; }
            });
        }

        for (Thread t : all) t.join();

        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(1);
        }
        map.forEach((k, v) -> {
            if (v != (long) k * k) throw new AssertionError("final invariant violated k=" + k + " v=" + v);
        });
        System.out.println("PASS LockedMapStress: " + durationMs + " ms, final size=" + map.size());
    }

    private LockedMapStress() {}
}
