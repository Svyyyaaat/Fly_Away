package dev.flyaway.perf.debug;

import dev.flyaway.perf.FlyAwayPerf;
import dev.flyaway.perf.PerfConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client "stutter catcher". A daemon thread watches the render thread; when no frame boundary has been reached for
 * longer than debug.hitchMs (default 150 ms) it samples the render thread's stack every 5 ms until the frame finishes,
 * then appends a summary (duration, hottest stacks, hottest methods) to logs/flyaway-hitches.log.
 * Costs nothing while frames are normal (one volatile write per frame; the watchdog sleeps). Stack sampling only
 * happens during an ongoing hitch. Disable: debug.hitchLogger=false.
 */
public final class HitchLogger {
    private static final int THRESHOLD_MS = PerfConfig.getInt("debug.hitchMs", 150);
    private static final long MAX_FILE = 8L << 20;
    private static volatile long lastBoundary = System.nanoTime();
    private static volatile Thread renderThread;
    private static final boolean IN_MENUS = PerfConfig.getBool("debug.hitchInMenus", false);
    private static long reportedStart = -1;

    private HitchLogger() {}

    public static void init() {
        if (!PerfConfig.getBool("debug.hitchLogger", true)) return;
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre e) -> {
            renderThread = Thread.currentThread();
            lastBoundary = System.nanoTime();
        });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> lastBoundary = System.nanoTime());
        Thread t = new Thread(HitchLogger::run, "FlyAway-HitchLogger");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void run() {
        long thresholdNs = THRESHOLD_MS * 1_000_000L;
        while (true) {
            try {
                Thread.sleep(20);
                Thread rt = renderThread;
                long start = lastBoundary;
                if (rt == null || start == reportedStart || System.nanoTime() - start < thresholdNs) continue;
                if (!IN_MENUS && Minecraft.getInstance().level == null) continue; // don't sample loading screens
                // Hitch in progress: sample until the render thread passes a frame boundary again.
                List<StackTraceElement[]> samples = new ArrayList<>();
                while (lastBoundary == start && samples.size() < 4000) {
                    samples.add(rt.getStackTrace());
                    Thread.sleep(5);
                }
                long durMs = ((lastBoundary == start ? System.nanoTime() : lastBoundary) - start) / 1_000_000L;
                reportedStart = start; // one report per freeze, even past the sample cap
                report(durMs, samples);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                FlyAwayPerf.LOGGER.warn("[hitch] logger error", t);
            }
        }
    }

    private static boolean isBoilerplate(String m) {
        return m.startsWith("cpw.mods.") || m.startsWith("net.neoforged.fml.loading.") || m.startsWith("java.lang.reflect.")
                || m.startsWith("jdk.internal.reflect.") || m.startsWith("java.lang.invoke.") || m.equals("net.minecraft.client.main.Main.main")
                || m.equals("net.minecraft.client.Minecraft.run") || m.startsWith("org.prismlauncher.");
    }

    private static void report(long durMs, List<StackTraceElement[]> samples) throws IOException {
        if (samples.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel lvl = mc.level; // read once: may change on another thread
        if (lvl == null && !IN_MENUS) return;
        String where = lvl == null ? "no world (loading/menu)" : lvl.dimension().location().toString();
        Map<String, Integer> stacks = new HashMap<>();
        Map<String, Integer> methods = new HashMap<>();
        for (StackTraceElement[] st : samples) {
            StringBuilder sb = new StringBuilder();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < st.length; i++) {
                String m = st[i].getClassName() + "." + st[i].getMethodName();
                if (!isBoilerplate(m) && seen.add(m)) methods.merge(m, 1, Integer::sum);
                if (i < 30) sb.append("    ").append(st[i]).append('\n');
            }
            stacks.merge(sb.toString(), 1, Integer::sum);
        }
        int n = samples.size();
        StringBuilder out = new StringBuilder();
        out.append("=== ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append("  hitch ").append(durMs).append(" ms  (").append(n).append(" samples, ").append(where).append(")\n");
        out.append("  hottest methods (inclusive % of samples):\n");
        methods.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(45)
                .forEach(e -> out.append(String.format("    %5.1f%%  %s%n", 100.0 * e.getValue() / n, e.getKey())));
        out.append("  hottest stacks:\n");
        stacks.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                .forEach(e -> out.append(String.format("  -- %d samples%n", e.getValue())).append(e.getKey()));
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("flyaway-hitches.log");
        Files.createDirectories(file.getParent());
        if (Files.exists(file) && Files.size(file) > MAX_FILE) {
            Files.move(file, file.resolveSibling("flyaway-hitches.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        FlyAwayPerf.LOGGER.info("[hitch] {} ms frame recorded to logs/flyaway-hitches.log", durMs);
    }
}
