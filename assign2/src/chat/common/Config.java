package chat.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Properties loader. Missing path = empty config = all defaults.
 */
public final class Config {
    private final Properties props = new Properties();

    public Config() {}

    public Config(Path path) throws IOException {
        if (path != null && Files.exists(path)) {
            try (var r = Files.newBufferedReader(path)) {
                props.load(r);
            }
        }
    }

    public String getString(String key, String def) {
        return props.getProperty(key, def);
    }

    public int getInt(String key, int def) {
        String v = props.getProperty(key);
        return (v == null) ? def : Integer.parseInt(v.trim());
    }

    public boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        return (v == null) ? def : Boolean.parseBoolean(v.trim());
    }
}
