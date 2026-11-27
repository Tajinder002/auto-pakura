package dev.autoparkour;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;

public class ParkourClient implements ClientModInitializer {
    // 1.21.9+ keybinds need a Category object
    static final KeyBinding.Category CAT = KeyBinding.Category.create(Identifier.of("autoparkour", "main"));
    private static KeyBinding recordKey, playKey, clearKey;

    private enum Mode { IDLE, RECORDING, PLAYING }
    private Mode mode = Mode.IDLE;
    private Run recording;
    private Run playing;
    private int idx;
    private int lostTicks;

    private static final int SEARCH_BACK = 5, SEARCH_AHEAD = 25;
    private static final double MAX_DRIFT = 2.5;     // blocks away from the run before we give up
    private static final int MAX_LOST_TICKS = 10;

    @Override
    public void onInitializeClient() {
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.record", GLFW.GLFW_KEY_R, CAT));
        playKey   = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.play",   GLFW.GLFW_KEY_P, CAT));
        clearKey  = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.clear",  GLFW.GLFW_KEY_BACKSLASH, CAT));
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        new AutoGo().init();
    }

    static String mapId(MinecraftClient mc) {
        // Singleplayer: world folder name. Multiplayer/realm: server address.
        if (mc.getServer() != null) return mc.getServer().getSaveProperties().getLevelName().replaceAll("[^a-zA-Z0-9_-]", "_");
        if (mc.getCurrentServerEntry() != null) return mc.getCurrentServerEntry().address.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return "unknown";
    }

    private void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoParkour] " + s), true);
    }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) { mode = Mode.IDLE; return; }

        while (recordKey.wasPressed()) {
            if (mode == Mode.RECORDING) stopRecording(mc);
            else if (mode == Mode.IDLE) { recording = new Run(); mode = Mode.RECORDING; msg(mc, "Recording... press again to save"); }
        }
        while (playKey.wasPressed()) {
            if (mode == Mode.PLAYING) stopPlaying(mc, "Stopped");
            else if (mode == Mode.IDLE) startPlaying(mc);
        }
        while (clearKey.wasPressed()) msg(mc, "Deleted " + Run.clear(mapId(mc)) + " runs");

        if (mode == Mode.RECORDING) record(mc, p);
        else if (mode == Mode.PLAYING) play(mc, p);
    }

    // ---------- record ----------
    private void record(MinecraftClient mc, ClientPlayerEntity p) {
        GameOptions o = mc.options;
        recording.frames.add(new Frame(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(),
                o.forwardKey.isPressed(), o.backKey.isPressed(), o.leftKey.isPressed(), o.rightKey.isPressed(),
                o.jumpKey.isPressed(), o.sneakKey.isPressed(), o.sprintKey.isPressed()));
    }

    private void stopRecording(MinecraftClient mc) {
        mode = Mode.IDLE;
        try {
            recording.save(mapId(mc));
            msg(mc, "Saved run (" + recording.frames.size() + " ticks)");
        } catch (IOException e) { msg(mc, "Could not save: " + e.getMessage()); }
        recording = null;
    }

    // ---------- play ----------
    private void startPlaying(MinecraftClient mc) {
        playing = Run.loadBest(mapId(mc));
        if (playing == null) { msg(mc, "No saved runs for this map. Record one first."); return; }
        // start from the frame closest to where we stand now
        idx = nearest(playing, mc.player, 0, playing.frames.size() - 1);
        lostTicks = 0;
        mode = Mode.PLAYING;
        msg(mc, "Playing best run (" + playing.frames.size() + " ticks). Press again to stop.");
    }

    private void stopPlaying(MinecraftClient mc, String why) {
        mode = Mode.IDLE;
        releaseKeys(mc.options);
        msg(mc, why);
    }

    private int nearest(Run r, ClientPlayerEntity p, int from, int to) {
        int best = Math.max(0, from);
        double bd = Double.MAX_VALUE;
        for (int i = Math.max(0, from); i <= Math.min(to, r.frames.size() - 1); i++) {
            double d = r.frames.get(i).distTo(p.getX(), p.getY(), p.getZ());
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private void play(MinecraftClient mc, ClientPlayerEntity p) {
        // Drift correction: re-sync to the nearest recorded tick instead of blindly counting ticks.
        int near = nearest(playing, p, idx - SEARCH_BACK, idx + SEARCH_AHEAD);
        double d = playing.frames.get(near).distTo(p.getX(), p.getY(), p.getZ());
        if (d > MAX_DRIFT) {
            if (++lostTicks > MAX_LOST_TICKS) { stopPlaying(mc, "Lost the route (too far off)"); return; }
        } else lostTicks = 0;
        idx = Math.max(idx, near);              // never go backwards

        if (idx >= playing.frames.size() - 1) { stopPlaying(mc, "Run finished"); return; }

        Frame f = playing.frames.get(idx);
        GameOptions o = mc.options;
        o.forwardKey.setPressed(f.fwd());
        o.backKey.setPressed(f.back());
        o.leftKey.setPressed(f.left());
        o.rightKey.setPressed(f.right());
        o.jumpKey.setPressed(f.jump());
        o.sneakKey.setPressed(f.sneak());
        o.sprintKey.setPressed(f.sprint());
        // smooth aim toward the recorded look direction
        p.setYaw(p.getYaw() + MathHelper.wrapDegrees(f.yaw() - p.getYaw()) * 0.5f);
        p.setPitch(p.getPitch() + (f.pitch() - p.getPitch()) * 0.5f);
        idx++;
    }

    private void releaseKeys(GameOptions o) {
        o.forwardKey.setPressed(false); o.backKey.setPressed(false);
        o.leftKey.setPressed(false);    o.rightKey.setPressed(false);
        o.jumpKey.setPressed(false);    o.sneakKey.setPressed(false);
        o.sprintKey.setPressed(false);
    }
}
