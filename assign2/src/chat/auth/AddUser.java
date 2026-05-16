package chat.auth;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** CLI helper: java chat.auth.AddUser <users-file> <username> [password] */
public final class AddUser {
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: java chat.auth.AddUser <users-file> <username> [password]");
            System.exit(2);
        }

        Path usersPath = Path.of(args[0]);
        String username = args[1];
        char[] password = null;
        boolean fromArgs = args.length == 3;

        try {
            if (fromArgs) {
                password = args[2].toCharArray();
            } else {
                Console console = System.console();
                if (console == null) {
                    System.err.println("no console available; pass password as third argument");
                    System.exit(2);
                }
                password = console.readPassword("Password for %s: ", username);
                char[] confirm = console.readPassword("Confirm password: ");
                if (!Arrays.equals(password, confirm)) {
                    System.err.println("passwords do not match");
                    System.exit(2);
                }
                PasswordHasher.clear(confirm);
            }

            PasswordHasher hasher = new PasswordHasher();
            UsersFile usersFile = new UsersFile(usersPath, hasher);
            UserRegistry registry = UserRegistry.loadFrom(usersFile);

            if (registry.register(username, password)) {
                System.out.println("added user: " + User.normalizeUsername(username));
            } else {
                System.err.println("user already exists: " + User.normalizeUsername(username));
                System.exit(1);
            }
        } finally {
            PasswordHasher.clear(password);
        }
    }

    private AddUser() {}
}
