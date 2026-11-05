package dev.farview.client.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import dev.farview.client.FarViewClient;

public final class FarViewConfig {
    private static final int[] DISTANCE_PRESETS = {128, 256, 512, 1024};
    private final Path file;
    private boolean enabled = true;
    private int maxDistanceChunks = 1024;
    private int memoryBudgetMb = 512;

    private FarViewConfig(Path file) {
        this.file = file;
    }

    public static FarViewConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("farview.properties");
        FarViewConfig config = new FarViewConfig(file);
        if (Files.exists(file)) {
            Properties properties = new Properties();
            try (var reader = Files.newBufferedReader(file)) {
                properties.load(reader);
                config.enabled = Boolean.parseBoolean(properties.getProperty("enabled", "true"));
                config.maxDistanceChunks = clampDistance(parseInt(properties.getProperty("maxDistanceChunks"), 1024));
                config.memoryBudgetMb = Math.clamp(parseInt(properties.getProperty("memoryBudgetMb"), 512), 128, 16384);
            } catch (IOException e) {
                FarViewClient.LOGGER.warn("Could not read FarView settings; using defaults", e);
            }
        }
        config.save();
        return config;
    }

    public synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("maxDistanceChunks", Integer.toString(maxDistanceChunks));
        properties.setProperty("memoryBudgetMb", Integer.toString(memoryBudgetMb));
        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file)) {
                properties.store(writer, "FarView client settings");
            }
        } catch (IOException e) {
            FarViewClient.LOGGER.warn("Could not save FarView settings", e);
        }
    }

    public static Path cacheRoot() {
        return FabricLoader.getInstance().getConfigDir().resolve("farview").resolve("cache");
    }

    public synchronized void cycleDistance() {
        int next = 0;
        for (int i = 0; i < DISTANCE_PRESETS.length; i++) {
            if (DISTANCE_PRESETS[i] == maxDistanceChunks) next = (i + 1) % DISTANCE_PRESETS.length;
        }
        maxDistanceChunks = DISTANCE_PRESETS[next];
        save();
    }

    public synchronized void toggleEnabled() {
        enabled = !enabled;
        save();
    }

    public synchronized boolean enabled() { return enabled; }
    public synchronized int maxDistanceChunks() { return maxDistanceChunks; }
    public synchronized int memoryBudgetMb() { return memoryBudgetMb; }

    private static int clampDistance(int value) {
        int best = DISTANCE_PRESETS[0];
        for (int preset : DISTANCE_PRESETS) if (Math.abs(preset - value) < Math.abs(best - value)) best = preset;
        return best;
    }

    private static int parseInt(String text, int fallback) {
        try { return Integer.parseInt(text); } catch (RuntimeException ignored) { return fallback; }
    }
}
