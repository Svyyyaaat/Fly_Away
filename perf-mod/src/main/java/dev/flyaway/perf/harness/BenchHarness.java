package dev.flyaway.perf.harness;

import dev.flyaway.perf.FlyAwayPerf;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Developer benchmark driver - only active with -Dflyaway.harness=bench. Never runs in normal play.
 * Creates/loads a fixed world, runs a deterministic scene (fixed time/weather, NoAI mobs, slow camera spin),
 * records per-frame wall and render-thread CPU time, optionally runs spark on all threads, then quits.
 * Results: <gamedir>/flyaway_bench/result-<label>.txt
 */
public final class BenchHarness {
    private static final String WORLD = System.getProperty("flyaway.harness.world", "perftest");
    private static final long SEED = Long.getLong("flyaway.harness.seed", 7140271L);
    private static final int WARMUP_S = Integer.getInteger("flyaway.harness.warmup", 90);
    private static final int MEASURE_S = Integer.getInteger("flyaway.harness.duration", 60);
    private static final boolean SPARK = Boolean.parseBoolean(System.getProperty("flyaway.harness.spark", "true"));
    private static final String LABEL = System.getProperty("flyaway.harness.label", "run");
    private static final String SETUP = System.getProperty("flyaway.harness.setup", "");

    private enum Phase { BOOT, LOADING, WARMUP, MEASURE, COOLDOWN, DONE }

    private static Phase phase = Phase.BOOT;
    private static long phaseStartNanos;
    private static long worldJoinNanos;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static long frameStartWall, frameStartCpu;
    private static double[] frameMs = new double[1 << 16];
    private static double[] cpuMs = new double[1 << 16];
    private static int frames;
    private static double serverMsptSum;
    private static int serverSamples;
    private static int tickCount;

    private BenchHarness() {}

    public static void init() {
        if (!"bench".equals(System.getProperty("flyaway.harness"))) return;
        FlyAwayPerf.LOGGER.warn("[bench] harness ACTIVE: world={} warmup={}s measure={}s label={}", WORLD, WARMUP_S, MEASURE_S, LABEL);
        NeoForge.EVENT_BUS.addListener(BenchHarness::onTick);
        NeoForge.EVENT_BUS.addListener(BenchHarness::onFramePre);
        NeoForge.EVENT_BUS.addListener(BenchHarness::onFramePost);
    }

    private static double sinceS(long t) {
        return (System.nanoTime() - t) / 1e9;
    }

    private static void setPhase(Phase p) {
        phase = p;
        phaseStartNanos = System.nanoTime();
        FlyAwayPerf.LOGGER.warn("[bench] phase -> {}", p);
    }

    private static void onFramePre(RenderFrameEvent.Pre e) {
        if (phase != Phase.MEASURE) return;
        frameStartWall = System.nanoTime();
        frameStartCpu = THREADS.getCurrentThreadCpuTime();
    }

    private static void onFramePost(RenderFrameEvent.Post e) {
        if (phase != Phase.MEASURE || frameStartWall == 0) return;
        if (frames == frameMs.length) {
            frameMs = Arrays.copyOf(frameMs, frames * 2);
            cpuMs = Arrays.copyOf(cpuMs, frames * 2);
        }
        // Includes everything between frame start and end of GameRenderer.render (not swap/limiter).
        frameMs[frames] = (System.nanoTime() - frameStartWall) / 1e6;
        cpuMs[frames] = (THREADS.getCurrentThreadCpuTime() - frameStartCpu) / 1e6;
        frames++;
    }

    private static void cmd(Minecraft mc, String c) {
        if (mc.player == null) return;
        if (c.startsWith("sparkc ") && ClientCommandHandler.runCommand(c)) return;
        mc.player.connection.sendCommand(c);
    }

    private static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        tickCount++;
        switch (phase) {
            case BOOT -> {
                if (mc.screen instanceof TitleScreen && tickCount > 40) {
                    setPhase(Phase.LOADING);
                    Path save = FMLPaths.GAMEDIR.get().resolve("saves").resolve(WORLD);
                    if (Files.isDirectory(save)) {
                        mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
                    } else {
                        GameRules rules = new GameRules();
                        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules,
                                WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(SEED, true, false),
                                WorldPresets::createNormalWorldDimensions, new TitleScreen());
                    }
                }
            }
            case LOADING -> {
                if (mc.level != null && mc.player != null && mc.screen == null) {
                    worldJoinNanos = System.nanoTime();
                    setPhase(Phase.WARMUP);
                    cmd(mc, "time set 6000");
                    cmd(mc, "weather clear");
                    cmd(mc, "gamerule doDaylightCycle false");
                    cmd(mc, "gamerule doMobSpawning false");
                    cmd(mc, "gamemode creative");
                    // A few NoAI mobs in front of the camera path so entity rendering (Epic Fight, layers) is exercised.
                    String[] mobs = {"zombie", "skeleton", "husk", "villager", "pillager", "vindicator", "wither_skeleton", "creeper"};
                    for (int i = 0; i < mobs.length; i++) {
                        double a = i * Math.PI * 2 / mobs.length;
                        cmd(mc, String.format(Locale.ROOT, "summon minecraft:%s ~%.1f ~ ~%.1f {NoAI:1b,PersistenceRequired:1b}",
                                mobs[i], Math.cos(a) * 6, Math.sin(a) * 6));
                    }
                    for (String c : SETUP.split(";")) if (!c.isBlank()) cmd(mc, c.trim());
                }
            }
            case WARMUP -> {
                spin(mc);
                if (sinceS(phaseStartNanos) >= WARMUP_S) {
                    setPhase(Phase.MEASURE);
                    if (SPARK) {
                        cmd(mc, "sparkc profiler start --thread * --interval 4 --force-java-sampler --timeout " + MEASURE_S + " --save-to-file");
                        cmd(mc, "spark profiler start --thread * --interval 4 --force-java-sampler --timeout " + MEASURE_S + " --save-to-file");
                    }
                }
            }
            case MEASURE -> {
                spin(mc);
                MinecraftServer srv = mc.getSingleplayerServer();
                if (srv != null && tickCount % 20 == 0) {
                    serverMsptSum += srv.getCurrentSmoothedTickTime();
                    serverSamples++;
                }
                if (sinceS(phaseStartNanos) >= MEASURE_S) {
                    writeResult(mc);
                    setPhase(Phase.COOLDOWN);
                }
            }
            case COOLDOWN -> {
                if (sinceS(phaseStartNanos) >= 20) {
                    setPhase(Phase.DONE);
                    mc.stop();
                }
            }
            case DONE -> {
            }
        }
    }

    private static void spin(Minecraft mc) {
        if (mc.player == null) return;
        mc.player.getAbilities().flying = true;
        mc.player.setYRot(mc.player.getYRot() + 1.5f);
        mc.player.setXRot(10f);
    }

    private static double pct(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))];
    }

    private static void writeResult(Minecraft mc) {
        double[] f = Arrays.copyOf(frameMs, frames);
        double[] c = Arrays.copyOf(cpuMs, frames);
        double sumF = 0, sumC = 0;
        for (int i = 0; i < frames; i++) {
            sumF += f[i];
            sumC += c[i];
        }
        Arrays.sort(f);
        Arrays.sort(c);
        double secs = sinceS(phaseStartNanos);
        String out = String.format(Locale.ROOT,
                "label=%s%nframes=%d%nseconds=%.1f%nfps=%.2f%nframe_ms_avg=%.3f%nframe_ms_p50=%.3f%nframe_ms_p95=%.3f%nframe_ms_p99=%.3f%n"
                        + "render_cpu_ms_avg=%.3f%nrender_cpu_ms_p50=%.3f%nrender_cpu_ms_p95=%.3f%nserver_mspt_avg=%.3f%n",
                LABEL, frames, secs, frames / secs, sumF / Math.max(1, frames), pct(f, .5), pct(f, .95), pct(f, .99),
                sumC / Math.max(1, frames), pct(c, .5), pct(c, .95), serverMsptSum / Math.max(1, serverSamples));
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("flyaway_bench");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("result-" + LABEL + ".txt"), out);
        } catch (IOException ex) {
            FlyAwayPerf.LOGGER.error("[bench] could not write result", ex);
        }
        FlyAwayPerf.LOGGER.warn("[bench] RESULT\n{}", out);
    }
}
