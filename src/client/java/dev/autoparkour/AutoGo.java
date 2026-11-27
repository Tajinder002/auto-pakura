package dev.autoparkour;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * C = add checkpoint (block you look at), G = go / stop, X = clear checkpoints, V = debug view on/off.
 * Plans with the physics sim, executes the key tape, compares prediction with reality, learns from misses.
 */
public final class AutoGo {
    private enum S { IDLE, PLAN, POS, TAPE, SETTLE }

    public static AutoGo I;
    private KeyBinding addKey, goKey, clearKey, debugKey, guiKey;
    private final List<BlockPos> checkpoints = new ArrayList<>();
    private S state = S.IDLE;
    private Planner planner;
    private final ArrayDeque<Planner.Act> path = new ArrayDeque<>();
    private Planner.Act act;
    private int tapeIdx, settleTicks, replans, moveNo, moveTotal, devFirst;
    private double devMax, startErr;
    private boolean debug = true, relaxed;
    private String map = "unknown";

    public void init() {
        I = this;
        addKey   = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.add",      GLFW.GLFW_KEY_C, ParkourClient.CAT));
        goKey    = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.go",       GLFW.GLFW_KEY_G, ParkourClient.CAT));
        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.cp_clear", GLFW.GLFW_KEY_X, ParkourClient.CAT));
        debugKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.debug",    GLFW.GLFW_KEY_V, ParkourClient.CAT));
        guiKey   = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autoparkour.gui",      GLFW.GLFW_KEY_K, ParkourClient.CAT));
        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
    }

    private void msg(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoParkour] " + s), true);
    }

    private void release(GameOptions o) {
        o.forwardKey.setPressed(false); o.backKey.setPressed(false); o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);   o.jumpKey.setPressed(false); o.sneakKey.setPressed(false);
        o.sprintKey.setPressed(false);
    }

    private void stop(MinecraftClient mc, String why) {
        state = S.IDLE; planner = null; path.clear(); act = null;
        release(mc.options);
        msg(mc, why);
    }

    private BlockPos here(ClientPlayerEntity p) { return BlockPos.ofFloored(p.getX(), p.getY() + 0.01, p.getZ()); }
    private static String fmt(BlockPos b) { return b.getX() + "," + b.getY() + "," + b.getZ(); }

    private void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) { state = S.IDLE; return; }

        while (addKey.wasPressed()) {
            if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                checkpoints.add(hit.getBlockPos().toImmutable());
                msg(mc, "Checkpoint " + checkpoints.size() + " added (" + fmt(hit.getBlockPos()) + ")");
            } else msg(mc, "Look at a block first");
        }
        while (clearKey.wasPressed()) { checkpoints.clear(); msg(mc, "Checkpoints cleared"); }
        while (debugKey.wasPressed()) { debug = !debug; msg(mc, "Debug view " + (debug ? "on" : "off")); }
        while (goKey.wasPressed()) toggleGo(mc);
        while (guiKey.wasPressed()) { if (mc.currentScreen == null) mc.setScreen(new ParkourScreen()); }

        // safety stops
        if (state != S.IDLE && (mc.currentScreen != null || p.hurtTime > 0)) {
            stop(mc, mc.currentScreen != null ? "Stopped (menu opened)" : "Stopped (you took damage)");
        }

        switch (state) {
            case PLAN -> {
                planner.step(6_000_000L);
                if (planner.done) { path.clear(); path.addAll(planner.path); moveTotal = path.size(); moveNo = 0; nextAct(mc); }
                else if (planner.failed) {
                    if (planner.margin > 0) {
                        relaxed = true;
                        msg(mc, "No safe route, trying tight jumps (less safe)");
                        startPlan(mc, 0);
                    } else stop(mc, "No route found");
                } else if (p.age % 20 == 0) msg(mc, "Planning... " + planner.expanded + " positions checked");
            }
            case POS -> position(mc, p);
            case TAPE -> {
                if (tapeIdx < act.tape.size()) {
                    int i = tapeIdx++;
                    Sim.Inp in = act.tape.get(i);
                    Vec3d pred = act.traj.get(i);
                    double dev = Math.sqrt(sq(p.getX() - pred.x) + sq(p.getY() - pred.y) + sq(p.getZ() - pred.z));
                    if (dev > devMax) devMax = dev;
                    if (devFirst < 0 && dev > 0.1) devFirst = i;
                    GameOptions o = mc.options;
                    o.forwardKey.setPressed(in.fwd()); o.jumpKey.setPressed(in.jump());
                    o.sneakKey.setPressed(in.sneak()); o.sprintKey.setPressed(in.sprint());
                    o.backKey.setPressed(false); o.leftKey.setPressed(false); o.rightKey.setPressed(false);
                    p.setYaw(in.yaw());
                    if (i % 4 == 0) msg(mc, String.format("Move %d/%d  tick %d/%d  off %.2f%s", moveNo, moveTotal, i, act.tape.size(), dev, relaxed ? "  (tight)" : ""));
                } else { release(mc.options); state = S.SETTLE; settleTicks = 0; }
            }
            case SETTLE -> {
                settleTicks++;
                boolean still = p.isOnGround() && p.getVelocity().horizontalLengthSquared() < 1e-4;
                if (still || settleTicks > 25) finishMove(mc, p);
            }
            default -> {}
        }
        viz(mc, p);
    }

    private static double sq(double d) { return d * d; }

    private void finishMove(MinecraftClient mc, ClientPlayerEntity p) {
        BlockPos at = here(p);
        boolean ok = at.equals(act.to);
        double err = Math.hypot(p.getX() - act.endPos.x, p.getZ() - act.endPos.z);
        Learn.record(map, act.key, ok, err);
        Log.write(map, String.format("%s from=%s to=%s landed=%s err=%.3f maxDev=%.3f firstDev=%d startErr=%.3f ticks=%d margin=%.2f key=%s",
                ok ? "OK  " : "MISS", fmt(act.from), fmt(act.to), fmt(at), err, devMax, devFirst, startErr, act.tape.size(), planner == null ? 0 : planner.margin, act.key));
        if (ok) nextAct(mc);
        else replan(mc, String.format("Missed the landing by %.2f, learning and re-planning", err));
    }

    private void startPlan(MinecraftClient mc, double margin) {
        planner = new Planner(mc.world, here(mc.player), checkpoints.get(0), margin);
        state = S.PLAN;
        msg(mc, String.format("Planning route to checkpoint 1 of %d (safety %.2f)", checkpoints.size(), margin));
    }

    private void replan(MinecraftClient mc, String why) {
        if (++replans > 6) { stop(mc, "Too many misses, stopping (see the log file)"); return; }
        release(mc.options);
        msg(mc, why);
        startPlan(mc, relaxed ? 0 : Learn.margin());
    }

    private void nextAct(MinecraftClient mc) {
        if (path.isEmpty()) {
            checkpoints.remove(0);
            if (checkpoints.isEmpty()) { stop(mc, "All checkpoints reached"); return; }
            replans = 0; relaxed = false;
            startPlan(mc, Learn.margin());
            return;
        }
        act = path.poll();
        moveNo++;
        state = S.POS;
    }

    /** Walk (sneaking, so never off an edge) to the exact start spot of the next move. */
    private void position(MinecraftClient mc, ClientPlayerEntity p) {
        GameOptions o = mc.options;
        double dx = act.start.x - p.getX(), dz = act.start.z - p.getZ();
        double d = Math.hypot(dx, dz);
        if (d > 1.6 || p.getY() < act.start.y - 0.8) { replan(mc, "Off route, re-planning"); return; }
        boolean still = p.getVelocity().horizontalLengthSquared() < 4e-4;
        if (d < 0.08) {
            release(o); o.sneakKey.setPressed(true);
            if (still) { release(o); tapeIdx = 0; devMax = 0; devFirst = -1; startErr = d; state = S.TAPE; }
        } else {
            p.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            o.forwardKey.setPressed(true); o.sneakKey.setPressed(true);
            o.sprintKey.setPressed(false); o.jumpKey.setPressed(false);
        }
    }

    // ---- used by the GUI ----
    public void toggleGo(MinecraftClient mc) {
        if (state != S.IDLE) stop(mc, "Stopped");
        else if (checkpoints.isEmpty()) msg(mc, "Add checkpoints first");
        else {
            map = ParkourClient.mapId(mc);
            Learn.ensure(map);
            replans = 0; relaxed = false; moveNo = 0;
            startPlan(mc, Learn.margin());
        }
    }
    public void halt(MinecraftClient mc) { if (state != S.IDLE) stop(mc, "Stopped"); }
    public List<BlockPos> checkpoints() { return checkpoints; }
    public boolean running() { return state != S.IDLE; }
    public boolean debug() { return debug; }
    public void setDebug(boolean b) { debug = b; }
    public int moveNo() { return moveNo; }
    public int moveTotal() { return moveTotal; }
    public int replans() { return replans; }
    public boolean tight() { return relaxed; }
    public String statusLine() {
        return switch (state) {
            case IDLE -> "Idle";
            case PLAN -> "Planning the route...";
            case POS -> "Moving to the jump spot";
            case TAPE -> "Doing the jump";
            case SETTLE -> "Landing";
        };
    }

    /** Debug view: green = checkpoints, white dots = planned landings, sparks = the predicted path of the current jump. */
    private void viz(MinecraftClient mc, ClientPlayerEntity p) {
        if (!debug || p.age % 6 != 0) return;
        for (BlockPos c : checkpoints)
            for (double y = 0; y < 1.6; y += 0.4)
                Viz.dot(mc, ParticleTypes.HAPPY_VILLAGER, c.getX() + 0.5, c.getY() + 1 + y, c.getZ() + 0.5);
        if (state == S.IDLE) return;
        for (Planner.Act a : path) Viz.dot(mc, ParticleTypes.END_ROD, a.to.getX() + 0.5, a.to.getY() + 0.2, a.to.getZ() + 0.5);
        if (act != null) {
            Viz.dot(mc, ParticleTypes.END_ROD, act.to.getX() + 0.5, act.to.getY() + 0.2, act.to.getZ() + 0.5);
            for (int i = 0; i < act.traj.size(); i += 3) {
                Vec3d t = act.traj.get(i);
                Viz.dot(mc, ParticleTypes.ELECTRIC_SPARK, t.x, t.y + 0.9, t.z);
            }
        }
    }
}
