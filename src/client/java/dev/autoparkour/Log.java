package dev.autoparkour;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalTime;

/** Every move writes one line to config/autoparkour/_logs/<map>.txt so mistakes can be studied. */
public final class Log {
    public static void write(String map, String line) {
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("autoparkour").resolve("_logs").resolve(map + ".txt");
            Files.createDirectories(f.getParent());
            Files.writeString(f, LocalTime.now().withNano(0) + " " + line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
