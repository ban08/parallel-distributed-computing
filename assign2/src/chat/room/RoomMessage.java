package chat.room;

import java.util.Objects;

/**
 * Immutable room timeline entry.
 */
public record RoomMessage(
        String author,
        long epochMillis,
        String text,
        boolean system
) {
    public RoomMessage {
        author = cleanAuthor(author, system);
        text = cleanText(text);
    }

    public String toFrame() {
        if (system) return "SYS " + text;
        return "MSG " + author + " " + epochMillis + " " + text;
    }

    private static String cleanAuthor(String author, boolean system) {
        if (system) return "";
        Objects.requireNonNull(author, "author");
        String value = author.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("author cannot be blank");
        if (hasFrameBreak(value)) throw new IllegalArgumentException("author cannot contain newline characters");
        return value;
    }

    private static String cleanText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) throw new IllegalArgumentException("text cannot be blank");
        if (hasFrameBreak(text)) throw new IllegalArgumentException("text cannot contain newline characters");
        return text;
    }

    private static boolean hasFrameBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }
}
