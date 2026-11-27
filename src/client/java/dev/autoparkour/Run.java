package dev.autoparkour;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** A saved run. Runs are stored per world/map so each parkour map learns its own best run. */
public class Run {
    private static final Gson GSON = new GsonBuilder().create();

    public List<Frame> frames = new ArrayList<>();

    public static Path dir(String mapId) {
        return FabricLoader.getInstance().getConfigDir().resolve("autoparkour").resolve(mapId);
    }

    public void save(String mapId) throws IOException {
        Files.createDirectories(dir(mapId));
        Path f = dir(mapId).resolve("run_" + System.currentTimeMillis() + ".json");
        Files.writeString(f, GSON.toJson(this));
    }

    /** Learning step: the best run is the fastest one (fewest ticks) that you have recorded. */
    public static Run loadBest(String mapId) {
        Path d = dir(mapId);
        if (!Files.isDirectory(d)) return null;
        Run best = null;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                try {
                    Run r = GSON.fromJson(Files.readString(p), Run.class);
                    if (r != null && r.frames.size() > 10 && (best == null || r.frames.size() < best.frames.size())) best = r;
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return best;
    }

    public static int clear(String mapId) {
        Path d = dir(mapId);
        int n = 0;
        if (!Files.isDirectory(d)) return 0;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s::iterator) { Files.deleteIfExists(p); n++; }
        } catch (IOException ignored) {}
        return n;
    }
}
