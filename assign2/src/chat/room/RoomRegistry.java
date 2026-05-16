package chat.room;

import chat.concurrent.LockedMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe room table. Registry locks are never held while touching a Room. */
public final class RoomRegistry {
    private final LockedMap<String, Room> rooms = new LockedMap<>();

    public Room create(String name) {
        return create(name, RoomKind.NORMAL);
    }

    public Room create(String name, RoomKind kind) {
        return register(new Room(name, kind));
    }

    public Room getOrCreateNormal(String name) {
        String normalized = Room.normalizeName(name);
        Room existing = rooms.get(normalized);
        if (existing != null) return existing;

        Room created = new Room(normalized, RoomKind.NORMAL);
        Room previous = rooms.putIfAbsent(normalized, created);
        return previous == null ? created : previous;
    }

    public Room register(Room room) {
        Objects.requireNonNull(room, "room");
        Room previous = rooms.putIfAbsent(room.name(), room);
        if (previous != null) {
            throw new IllegalArgumentException("room already exists: " + room.name());
        }
        return room;
    }

    public Room get(String name) {
        return rooms.get(Room.normalizeName(name));
    }

    public boolean exists(String name) {
        try {
            return rooms.containsKey(Room.normalizeName(name));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public int size() {
        return rooms.size();
    }

    public List<String> names() {
        List<String> names = new ArrayList<>(rooms.keys());
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public List<Room> snapshot() {
        Map<String, Room> copy = rooms.snapshot();
        List<Room> values = new ArrayList<>(copy.values());
        values.sort(Comparator.comparing(Room::name));
        return values;
    }
}
