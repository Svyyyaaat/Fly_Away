package dev.flyaway.perf;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * config/flyaway_perf.properties - every patch can be switched off without rebuilding.
 * Keys: "area.&lt;area&gt;=false" disables a whole group, "mixin.&lt;area&gt;.&lt;MixinSimpleName&gt;=false" one mixin.
 * Loaded very early (from the mixin plugin), so it must not touch any game class.
 */
public final class PerfConfig {
    private static final Properties PROPS = new Properties();
    private static final AtomicInteger APPLIED = new AtomicInteger();
    private static boolean loaded;

    private PerfConfig() {}

    public static synchronized void load(Path gameDir) {
        if (loaded) return;
        loaded = true;
        Path file = gameDir.resolve("config").resolve("flyaway_perf.properties");
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                PROPS.load(r);
            } catch (IOException ignored) {
            }
        } else {
            try {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(file)) {
                    w.write("# Fly Away performance patches\n"
                            + "# Disable a group:   area.<area>=false     (areas: vanilla, iris, imm_ptl, epicfight, xaero, sodium, flywheel, voxy, sable, vss, compat)\n"
                            + "# Disable one patch: mixin.<area>.<MixinClassName>=false\n");
                }
            } catch (IOException ignored) {
            }
        }
    }

    public static boolean isEnabled(String area, String mixinSimpleName) {
        if (!Boolean.parseBoolean(PROPS.getProperty("area." + area, "true"))) return false;
        return Boolean.parseBoolean(PROPS.getProperty("mixin." + area + "." + mixinSimpleName, "true"));
    }

    public static boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(PROPS.getProperty(key, Boolean.toString(def)));
    }

    public static String getString(String key, String def) {
        String v = PROPS.getProperty(key);
        return v == null ? def : v.trim();
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, Integer.toString(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void countApplied() {
        APPLIED.incrementAndGet();
    }

    public static int appliedCount() {
        return APPLIED.get();
    }
}
