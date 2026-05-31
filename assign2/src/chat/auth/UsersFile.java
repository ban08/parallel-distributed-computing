package chat.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads users from a simple UTF-8 text file: username:passwordHash.
 */
public final class UsersFile {
    private final Path path;

    public UsersFile(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /** Parses the complete users file, rejecting malformed or duplicate rows. */
    public Map<String, User> load() throws IOException {
        Map<String, User> users = new LinkedHashMap<>();
        if (!Files.exists(path)) throw new IOException("users file not found: " + path);

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int sep = line.indexOf(':');
            if (sep <= 0 || sep == line.length() - 1) {
                throw new IOException("invalid users file at line " + (i + 1));
            }

            User user = new User(line.substring(0, sep), line.substring(sep + 1));
            if (users.putIfAbsent(user.username(), user) != null) {
                throw new IOException("duplicate user in users file: " + user.username());
            }
        }
        return users;
    }
}
