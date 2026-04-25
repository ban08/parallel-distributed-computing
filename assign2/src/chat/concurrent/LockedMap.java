package chat.concurrent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Thread-safe map: HashMap behind a ReentrantReadWriteLock.
 *
 * Reads (get/containsKey/size/snapshot/keys/forEach) take the read lock and
 * may run concurrently. Mutations (put/putIfAbsent/computeIfAbsent/remove)
 * take the write lock.
 *
 * snapshot()/keys() return defensive copies — the caller cannot affect
 * internal state. forEach iterates under the read lock; the action runs
 * inside the critical section, so it must be cheap and must not call back
 * with a mutation (the read lock blocks subsequent writers).
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

    public V put(K key, V value) {
        rw.writeLock().lock();
        try { return map.put(key, value); }
        finally { rw.writeLock().unlock(); }
    }

    public V putIfAbsent(K key, V value) {
        rw.writeLock().lock();
        try { return map.putIfAbsent(key, value); }
        finally { rw.writeLock().unlock(); }
    }

    /**
     * Returns the existing value, or computes and inserts one. The mapping
     * runs under the write lock, so it must be cheap.
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mapping) {
        rw.writeLock().lock();
        try {
            V existing = map.get(key);
            if (existing != null) return existing;
            V created = mapping.apply(key);
            if (created != null) map.put(key, created);
            return created;
        } finally { rw.writeLock().unlock(); }
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

    /** Iterates under the read lock. The action must not mutate this map. */
    public void forEach(BiConsumer<? super K, ? super V> action) {
        rw.readLock().lock();
        try { map.forEach(action); }
        finally { rw.readLock().unlock(); }
    }
}
