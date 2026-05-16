package chat.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads and stores users in a simple UTF-8 text file: username:passwordHash. */
public final class UsersFile {
    private final Path path;
    private final PasswordHasher hasher;

    public UsersFile(Path path, PasswordHasher hasher) {
        this.path = Objects.requireNonNull(path, "path");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    public Map<String, User> load() throws IOException {
        Map<String, User> users = new LinkedHashMap<>();
        if (!Files.exists(path)) return users;

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

    public void save(Map<String, User> users) throws IOException {
        Objects.requireNonNull(users, "users");
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        StringBuilder sb = new StringBuilder();
        sb.append("# username:passwordHash\n");
        for (User user : users.values()) {
            sb.append(user.username()).append(':').append(user.passwordHash()).append('\n');
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public boolean addUser(String username, char[] password) throws IOException {
        String normalized = User.normalizeUsername(username);
        Map<String, User> users = load();
        if (users.containsKey(normalized)) return false;
        users.put(normalized, new User(normalized, hasher.hash(password)));
        save(users);
        return true;
    }

    /** Creates demo users only when the file does not exist yet. */
    public void createDemoUsersIfMissing() throws IOException {
        if (Files.exists(path)) return;

        Map<String, User> users = new LinkedHashMap<>();
        addDemo(users, "alice", "alice123".toCharArray());
        addDemo(users, "bob", "bob123".toCharArray());
        save(users);
    }

    private void addDemo(Map<String, User> users, String username, char[] password) {
        try {
            users.put(username, new User(username, hasher.hash(password)));
        } finally {
            PasswordHasher.clear(password);
        }
    }
}
