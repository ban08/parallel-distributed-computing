package chat.concurrent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded FIFO queue with blocking consumers and non-blocking producers.
 *
 * Single ReentrantLock with one Condition so producers can wake consumers.
 *
 * Predicates are checked inside while-loops, so spurious wake-ups are
 * harmless.
 *
 * Producers use offer instead of waiting: a slow client must never block room
 * progress.
 */
public final class BoundedQueue<E> {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Deque<E> deque;
    private final int capacity;

    public BoundedQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.deque = new ArrayDeque<>(capacity);
    }

    public int size() {
        lock.lock();
        try { return deque.size(); }
        finally { lock.unlock(); }
    }

    /** Removes and returns head; blocks while empty. */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (deque.isEmpty()) notEmpty.await();
            return deque.removeFirst();
        } finally { lock.unlock(); }
    }

    /** Inserts if room is immediately available; never blocks. */
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            if (deque.size() == capacity) return false;
            deque.addLast(e);
            notEmpty.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Moves all available elements into target. Returns the count moved. */
    public int drainTo(Collection<? super E> target) {
        Objects.requireNonNull(target);
        lock.lock();
        try {
            int n = 0;
            E e;
            while ((e = deque.pollFirst()) != null) {
                target.add(e);
                n++;
            }
            return n;
        } finally { lock.unlock(); }
    }
}
