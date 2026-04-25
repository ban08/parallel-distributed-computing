package chat.concurrent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded blocking FIFO queue. No null elements (matches BlockingQueue convention).
 *
 * Single ReentrantLock with two Conditions (notFull, notEmpty) so producers
 * and consumers wake each other selectively.
 *
 * Predicates are checked inside while-loops, so spurious wake-ups are
 * harmless. Timed waits use Condition.awaitNanos so the deadline is honoured
 * across spurious wake-ups (await(timeout) would restart the timer each loop).
 *
 * Mutators signal with signalAll; this is conservative but resilient to
 * future refactors that might consolidate the Conditions.
 */
public final class BoundedQueue<E> {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Deque<E> deque;
    private final int capacity;

    public BoundedQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.deque = new ArrayDeque<>(capacity);
    }

    public int capacity() { return capacity; }

    public int size() {
        lock.lock();
        try { return deque.size(); }
        finally { lock.unlock(); }
    }

    public int remainingCapacity() {
        lock.lock();
        try { return capacity - deque.size(); }
        finally { lock.unlock(); }
    }

    /** Inserts; blocks while full. */
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        lock.lockInterruptibly();
        try {
            while (deque.size() == capacity) notFull.await();
            deque.addLast(e);
            notEmpty.signalAll();
        } finally { lock.unlock(); }
    }

    /** Removes and returns head; blocks while empty. */
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (deque.isEmpty()) notEmpty.await();
            E e = deque.removeFirst();
            notFull.signalAll();
            return e;
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

    /** Inserts; waits up to (timeout, unit). Returns false on timeout. */
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        Objects.requireNonNull(unit);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (deque.size() == capacity) {
                if (nanos <= 0L) return false;
                nanos = notFull.awaitNanos(nanos);
            }
            deque.addLast(e);
            notEmpty.signalAll();
            return true;
        } finally { lock.unlock(); }
    }

    /** Removes head if any; never blocks. Returns null on empty. */
    public E poll() {
        lock.lock();
        try {
            if (deque.isEmpty()) return null;
            E e = deque.removeFirst();
            notFull.signalAll();
            return e;
        } finally { lock.unlock(); }
    }

    /** Removes; waits up to (timeout, unit). Returns null on timeout. */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (deque.isEmpty()) {
                if (nanos <= 0L) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            E e = deque.removeFirst();
            notFull.signalAll();
            return e;
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
            if (n > 0) notFull.signalAll();
            return n;
        } finally { lock.unlock(); }
    }
}
