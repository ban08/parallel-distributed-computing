package chat.concurrent;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Thread-safe set: HashSet behind a ReentrantReadWriteLock. Same locking
 * conventions as LockedMap. snapshot() returns a defensive copy; forEach
 * iterates under the read lock and must not call back with a mutation.
 */
public final class LockedSet<E> {

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Set<E> set = new HashSet<>();

    public boolean add(E e) {
        rw.writeLock().lock();
        try { return set.add(e); }
        finally { rw.writeLock().unlock(); }
    }

    public boolean remove(E e) {
        rw.writeLock().lock();
        try { return set.remove(e); }
        finally { rw.writeLock().unlock(); }
    }

    public boolean contains(E e) {
        rw.readLock().lock();
        try { return set.contains(e); }
        finally { rw.readLock().unlock(); }
    }

    public int size() {
        rw.readLock().lock();
        try { return set.size(); }
        finally { rw.readLock().unlock(); }
    }

    public Set<E> snapshot() {
        rw.readLock().lock();
        try { return new HashSet<>(set); }
        finally { rw.readLock().unlock(); }
    }

    public void forEach(Consumer<? super E> action) {
        rw.readLock().lock();
        try { set.forEach(action); }
        finally { rw.readLock().unlock(); }
    }
}
