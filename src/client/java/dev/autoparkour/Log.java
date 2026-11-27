package dev.autoparkour;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalTime;
import java.util.List;

/** Every move writes one line to config/autoparkour/_logs/<map>.txt so mistakes can be studied. */
public final class Log {
    public static void write(String map, String line) {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("autoparkour").resolve("_logs").resolve(map + ".txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, LocalTime.now().withNano(0) + " " + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    public static List<String> tail(String map, int n) {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("autoparkour").resolve("_logs").resolve(map + ".txt");
            if (!Files.exists(f)) return List.of();
            List<String> all = Files.readAllLines(f);
            return all.subList(Math.max(0, all.size() - n), all.size());
        } catch (IOException e) { return List.of(); }
    }
}
