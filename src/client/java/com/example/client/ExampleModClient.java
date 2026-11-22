package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class ExampleModClient implements ClientModInitializer {
    private static KeyMapping toggleKey;
    private static boolean show = true;
    private static final int N = 60;
    private static final float[] ft = new float[N];
    private static int idx = 0;
    private static long lastNs = 0;
    private static double batT = -1, cpuT = -1, freqRatio = -1;
    private static long gcPrev = 0, gcMsPerSec = 0;
    private static int tickN = 0;
    private static double fps = 0, avgMs = 0, worstMs = 0;
    private static boolean headerWritten = false;

    private static long readLong(String path) {
        try {
            return Long.parseLong(Files.readString(Path.of(path)).trim());
        } catch (Throwable e) {
            return Long.MIN_VALUE;
        }
    }

    private static long gcTime() {
        try {
            long t = 0;
            for (var b : ManagementFactory.getGarbageCollectorMXBeans()) {
                long x = b.getCollectionTime();
                if (x > 0) t += x;
            }
            return t;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void sample() {
        long b = readLong("/sys/class/power_supply/battery/temp");
        batT = b == Long.MIN_VALUE ? -1 : b / 10.0;

        double maxT = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/sys/class/thermal"), "thermal_zone*")) {
            for (Path z : ds) {
                long v = readLong(z.resolve("temp").toString());
                if (v != Long.MIN_VALUE) {
                    double c = v > 1000 ? v / 1000.0 : v;
                    if (c > maxT && c < 150) maxT = c;
                }
            }
        } catch (Throwable ignored) { }
        cpuT = maxT;

        double maxCur = -1, maxMax = -1;
        for (int i = 0; i < 16; i++) {
            long cur = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            long mx = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            if (cur != Long.MIN_VALUE && cur > maxCur) maxCur = cur;
            if (mx != Long.MIN_VALUE && mx > maxMax) maxMax = mx;
        }
        freqRatio = (maxCur > 0 && maxMax > 0) ? maxCur / maxMax : -1;

        long g = gcTime();
        gcMsPerSec = (g - gcPrev) / 2;
        gcPrev = g;
    }

    private static void log() {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("mobileperf-log.csv");
            StringBuilder sb = new StringBuilder();
            if (!headerWritten) {
                headerWritten = true;
                sb.append("time,fps,avg_ms,worst_ms,bat_c,cpu_c,freq_ratio,gc_ms_per_s,used_mb\n");
            }
            long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576;
            sb.append(System.currentTimeMillis() / 1000).append(',')
              .append(String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f,%.1f,%.1f,%.2f,%d,%d",
                      fps, avgMs, worstMs, batT, cpuT, freqRatio, gcMsPerSec, used)).append('\n');
            Files.writeString(f, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.mobileperf.toggle", InputConstants.KEY_J, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleKey.consumeClick()) show = !show;
            tickN++;
            if (tickN % 40 == 0) sample();
            if (tickN % 100 == 0 && mc.player != null) log();
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("mobileperf", "hud"), (g, dt) -> {
            long now = System.nanoTime();
            if (lastNs != 0) {
                ft[idx] = (now - lastNs) / 1_000_000f;
                idx = (idx + 1) % N;
            }
            lastNs = now;

            double sum = 0, worst = 0;
            for (float v : ft) {
                sum += v;
                if (v > worst) worst = v;
            }
            avgMs = sum / N;
            worstMs = worst;
            fps = avgMs > 0 ? 1000.0 / avgMs : 0;

            Minecraft mc = Minecraft.getInstance();
            if (!show || mc.options.hideGui) return;

            int x = 4, y = 30;
            g.text(mc.font, String.format("FPS %.0f  ort %.1fms  en kotu %.0fms", fps, avgMs, worstMs), x, y, 0xFFFFFFFF);
            y += 10;
            String t1 = String.format("BAT %s  CPU %s  hiz %s",
                    batT < 0 ? "?" : String.format("%.1fC", batT),
                    cpuT < 0 ? "?" : String.format("%.0fC", cpuT),
                    freqRatio < 0 ? "?" : String.format("%.0f%%", freqRatio * 100));
            g.text(mc.font, t1, x, y, 0xFF8EC8FF);
            y += 10;
            long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576;
            long max = Runtime.getRuntime().maxMemory() / 1048576;
            g.text(mc.font, "GC " + gcMsPerSec + "ms/sn  RAM " + used + "/" + max + "MB", x, y, 0xFFB57CFF);
            y += 12;

            for (int i = 0; i < N; i++) {
                float v = ft[(idx + i) % N];
                int h = (int) Math.min(v / 33.0f * 30.0f, 30);
                int col = v <= 17.5f ? 0xFF55FF55 : (v <= 33.5f ? 0xFFFFDD33 : 0xFFFF5555);
                g.fill(x + i * 2, y + 30 - h, x + i * 2 + 1, y + 30, col);
            }
        });
    }
}
