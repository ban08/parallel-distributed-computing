package chat.concurrent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe map: HashMap behind a ReentrantReadWriteLock.
 *
 * Reads (get/containsKey/size/snapshot/keys) take the read lock and may run
 * concurrently. Mutations (putIfAbsent/remove) take the write lock.
 *
 * snapshot()/keys() return defensive copies — the caller cannot affect
 * internal state.
 */
public final class LockedMap<K, V> {

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<K, V> map = new HashMap<>();

    public V get(K key) {
        rw.readLock().lock();
        try { return map.get(key); }
        finally { rw.readLock().unlock(); }
    }

    public boolean containsKey(K key) {
        rw.readLock().lock();
        try { return map.containsKey(key); }
        finally { rw.readLock().unlock(); }
    }

    public int size() {
        rw.readLock().lock();
        try { return map.size(); }
        finally { rw.readLock().unlock(); }
    }

    public V putIfAbsent(K key, V value) {
        rw.writeLock().lock();
        try { return map.putIfAbsent(key, value); }
        finally { rw.writeLock().unlock(); }
    }

    public V remove(K key) {
        rw.writeLock().lock();
        try { return map.remove(key); }
        finally { rw.writeLock().unlock(); }
    }

    /** Defensive copy; safe to iterate without external locking. */
    public Map<K, V> snapshot() {
        rw.readLock().lock();
        try { return new HashMap<>(map); }
        finally { rw.readLock().unlock(); }
    }

    /** Defensive copy of keys. */
    public Set<K> keys() {
        rw.readLock().lock();
        try { return new HashSet<>(map.keySet()); }
        finally { rw.readLock().unlock(); }
    }
}
