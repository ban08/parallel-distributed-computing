package chat.auth.stress;

import chat.auth.PasswordHasher;
import chat.auth.UserRegistry;
import chat.auth.UsersFile;

import java.nio.file.Files;
import java.nio.file.Path;

/** Concurrent registration/verification stress test for UserRegistry. */
public final class UserRegistryStress {
    private static final int THREADS = 8;
    private static final int USERS_PER_THREAD = 5;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("user-registry-stress-");
        Path usersPath = dir.resolve("users.txt");
        UserRegistry registry = UserRegistry.loadFrom(new UsersFile(usersPath, new PasswordHasher()));

        Throwable[] failure = new Throwable[1];
        Thread[] threads = new Thread[THREADS];

        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            threads[t] = Thread.ofVirtual().name("user-reg-" + t).start(() -> {
                try {
                    for (int i = 0; i < USERS_PER_THREAD; i++) {
                        String username = "u" + tid + "_" + i;
                        char[] password = ("pass_" + tid + "_" + i).toCharArray();
                        try {
                            if (!registry.register(username, password)) {
                                throw new AssertionError("first register failed for " + username);
                            }
                            if (registry.register(username, password)) {
                                throw new AssertionError("duplicate register succeeded for " + username);
                            }
                            if (!registry.verify(username, password)) {
                                throw new AssertionError("verify failed for " + username);
                            }
                            if (registry.verify(username, "wrong".toCharArray())) {
                                throw new AssertionError("wrong password accepted for " + username);
                            }
                        } finally {
                            PasswordHasher.clear(password);
                        }
                    }
                } catch (Throwable e) {
                    failure[0] = e;
                }
            });
        }

        for (Thread thread : threads) thread.join();

        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(1);
        }

        int expected = THREADS * USERS_PER_THREAD;
        if (registry.size() != expected) {
            System.err.println("FAIL UserRegistryStress: expected size " + expected + ", got " + registry.size());
            System.exit(1);
        }

        UserRegistry reloaded = UserRegistry.loadFrom(new UsersFile(usersPath, new PasswordHasher()));
        if (reloaded.size() != expected) {
            System.err.println("FAIL UserRegistryStress: persisted size " + reloaded.size() + ", expected " + expected);
            System.exit(1);
        }

        System.out.println("PASS UserRegistryStress: " + expected + " users registered, verified and persisted");
    }

    private UserRegistryStress() {}
}
