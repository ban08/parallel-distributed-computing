package chat.room;

/**
 * Something that can receive frames produced by a room.
 *
 * Implementations should not block room progress for a slow client. Returning
 * false means the frame was not accepted, typically because the outbound queue
 * is full or the subscriber is closed.
 */
public interface RoomSubscriber {
    /** Stable user name shown in room timelines. */
    String username();

    /** Enqueues one outbound frame for this subscriber. */
    boolean enqueue(String frame);

    /**
     * Enqueues a room timeline message. Sessions override this to keep sequence
     * metadata for reconnect replay; simple test subscribers can use the frame.
     */
    default boolean enqueueRoomMessage(String roomName, RoomMessage message) {
        return enqueue(message.toFrame());
    }
}
