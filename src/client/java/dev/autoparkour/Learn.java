package dev.autoparkour;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers, per map, which exact moves worked or missed, and how far off the landings are on average.
 * Moves that keep missing are banned; the average error widens the safety margin the planner demands.
 */
public final class Learn {
    private static final Gson GSON = new Gson();
    private static final class Data {
        Map<String, int[]> moves = new HashMap<>();   // key -> {ok, miss}
        double avgErr = 0;
        int n = 0;
    }
    private static Data d = new Data();
    private static String loaded = "";

    private static Path file(String map) {
        return FabricLoader.getInstance().getConfigDir().resolve("autoparkour").resolve("_learn").resolve(map + ".json");
    }

    public static void ensure(String map) {
        if (map.equals(loaded)) return;
        loaded = map;
        d = new Data();
        try {
            Path f = file(map);
            if (Files.exists(f)) { Data x = GSON.fromJson(Files.readString(f), Data.class); if (x != null && x.moves != null) d = x; }
        } catch (Exception ignored) {}
    }

    private static void save(String map) {
        try {
            Path f = file(map);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(d));
        } catch (IOException ignored) {}
    }

    public static String key(BlockPos from, float yaw, int runUp, boolean jump, double off) {
        return from.asLong() + ":" + Math.round(yaw) + ":" + runUp + ":" + (jump ? "J" : "W") + ":" + Math.round(off * 10);
    }

    public static boolean banned(String key) {
        int[] v = d.moves.get(key);
        return v != null && v[1] >= 2 && v[1] > v[0];
    }

    public static double penalty(String key) {
        int[] v = d.moves.get(key);
        return v == null ? 0 : v[1] * 8.0;
    }

    public static void record(String map, String key, boolean ok, double err) {
        int[] v = d.moves.computeIfAbsent(key, k -> new int[2]);
        v[ok ? 0 : 1]++;
        d.avgErr = d.n == 0 ? err : 0.8 * d.avgErr + 0.2 * err;
        d.n++;
        save(map);
    }

    /** How far off the start point we require a move to still work. Grows if landings are often off. */
    private static Double override;   // null = automatic
    public static void setOverride(Double v) { override = v; }
    public static Double override() { return override; }
    public static double margin() {
        if (override != null) return override;
        return Math.max(0.07, Math.min(0.2, 0.05 + d.avgErr * 0.7));
    }
    public static int movesTracked() { return d.moves.size(); }
    public static int bannedCount() { int c = 0; for (String k : d.moves.keySet()) if (banned(k)) c++; return c; }
    public static double avgErr() { return d.avgErr; }

    public static void reset(String map) { d = new Data(); loaded = map; save(map); }
}
