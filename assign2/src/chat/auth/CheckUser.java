package chat.auth;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;

/** CLI helper: java chat.auth.CheckUser <users-file> <username> [password] */
public final class CheckUser {
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: java chat.auth.CheckUser <users-file> <username> [password]");
            System.exit(2);
        }

        Path usersPath = Path.of(args[0]);
        String username = args[1];
        char[] password = null;

        try {
            if (args.length == 3) {
                password = args[2].toCharArray();
            } else {
                Console console = System.console();
                if (console == null) {
                    System.err.println("no console available; pass password as third argument");
                    System.exit(2);
                }
                password = console.readPassword("Password for %s: ", username);
            }

            UserRegistry registry = UserRegistry.loadFrom(usersPath);
            if (registry.verify(username, password)) {
                System.out.println("OK");
            } else {
                System.out.println("FAIL");
                System.exit(1);
            }
        } finally {
            PasswordHasher.clear(password);
        }
    }

    private CheckUser() {}
}
