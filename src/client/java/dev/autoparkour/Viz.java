package dev.autoparkour;

import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticleEffect;

public final class Viz {
    /** If this line does not compile on 1.21.11, rename addParticleClient to addParticle. */
    public static void dot(MinecraftClient mc, ParticleEffect e, double x, double y, double z) {
        mc.world.addParticleClient(e, x, y, z, 0, 0, 0);
    }
}
