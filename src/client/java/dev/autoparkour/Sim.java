package dev.autoparkour;

import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * A copy of Minecraft's player movement physics, run against the real world's block shapes.
 * The planner uses it to test a jump BEFORE doing it. Handles slipperiness (ice), stairs/slabs/trapdoors
 * (through their collision shapes), step-up, sneaking at edges and basic ladder climbing.
 * Not handled yet: slime/honey/soul sand, water, cobwebs, potion effects.
 */
public final class Sim {
    public record Inp(float yaw, boolean fwd, boolean jump, boolean sneak, boolean sprint) {}

    public static final class State {
        public double x, y, z, vx, vy, vz, fall, maxFall;
        public boolean onGround, bad;
        State(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        Box box() { return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3); }
        double speed2() { return vx * vx + vz * vz; }
    }

    public static final class Result {
        public final Vec3d start, endPos;
        public final List<Inp> tape;
        public final List<Vec3d> traj;   // traj[i] = predicted position BEFORE tape tick i
        public final BlockPos end;
        Result(Vec3d start, Vec3d endPos, List<Inp> tape, List<Vec3d> traj, BlockPos end) {
            this.start = start; this.endPos = endPos; this.tape = tape; this.traj = traj; this.end = end;
        }
    }

    // ---------------- collision ----------------
    private static List<VoxelShape> shapes(World w, Box b) {
        List<VoxelShape> l = new ArrayList<>();
        for (VoxelShape s : w.getBlockCollisions(null, b)) l.add(s);
        return l;
    }

    private static boolean free(World w, Box b) { return !w.getBlockCollisions(null, b).iterator().hasNext(); }

    private static Vec3d collide(World w, Box box, Vec3d mv) {
        List<VoxelShape> sh = shapes(w, box.stretch(mv));
        double dx = mv.x, dy = mv.y, dz = mv.z;
        if (dy != 0) { for (VoxelShape s : sh) dy = s.calculateMaxDistance(Direction.Axis.Y, box, dy); box = box.offset(0, dy, 0); }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0) { for (VoxelShape s : sh) dz = s.calculateMaxDistance(Direction.Axis.Z, box, dz); box = box.offset(0, 0, dz); }
        if (dx != 0) { for (VoxelShape s : sh) dx = s.calculateMaxDistance(Direction.Axis.X, box, dx); box = box.offset(dx, 0, 0); }
        if (!zFirst && dz != 0) { for (VoxelShape s : sh) dz = s.calculateMaxDistance(Direction.Axis.Z, box, dz); }
        return new Vec3d(dx, dy, dz);
    }

    private static Vec3d collideStep(World w, Box box, Vec3d mv, boolean onGround) {
        Vec3d adj = collide(w, box, mv);
        boolean hColl = adj.x != mv.x || adj.z != mv.z;
        boolean landing = mv.y < 0 && adj.y != mv.y;
        if (hColl && (onGround || landing)) {
            Vec3d s1 = collide(w, box, new Vec3d(0, 0.6, 0));
            Vec3d s2 = collide(w, box.offset(s1), new Vec3d(mv.x, 0, mv.z));
            Vec3d s3 = collide(w, box.offset(s1).offset(s2), new Vec3d(0, -s1.y + mv.y, 0));
            Vec3d st = s1.add(s2).add(s3);
            if (st.x * st.x + st.z * st.z > adj.x * adj.x + adj.z * adj.z) return st;
        }
        return adj;
    }

    /** Sneaking on the ground never walks you off an edge. */
    private static Vec3d sneakClip(World w, Box box, Vec3d mv) {
        double dx = mv.x, dz = mv.z, st = 0.05;
        while (dx != 0 && free(w, box.offset(dx, -0.6, 0))) dx = Math.abs(dx) < st ? 0 : dx - Math.signum(dx) * st;
        while (dz != 0 && free(w, box.offset(0, -0.6, dz))) dz = Math.abs(dz) < st ? 0 : dz - Math.signum(dz) * st;
        while (dx != 0 && dz != 0 && free(w, box.offset(dx, -0.6, dz))) {
            dx = Math.abs(dx) < st ? 0 : dx - Math.signum(dx) * st;
            dz = Math.abs(dz) < st ? 0 : dz - Math.signum(dz) * st;
        }
        return new Vec3d(dx, mv.y, dz);
    }

    // ---------------- one tick ----------------
    public static void tick(World w, State s, Inp in) {
        Box box = s.box();
        double yr = Math.toRadians(in.yaw());
        boolean sprinting = in.sprint() && in.fwd() && !in.sneak();

        if (in.jump() && s.onGround) {
            s.vy = 0.42;
            if (sprinting) { s.vx += -Math.sin(yr) * 0.2; s.vz += Math.cos(yr) * 0.2; }
        }

        float slip = s.onGround ? w.getBlockState(BlockPos.ofFloored(s.x, s.y - 0.500001, s.z)).getBlock().getSlipperiness() : 0.6f;
        double f = s.onGround ? slip * 0.91 : 0.91;
        double acc = s.onGround ? 0.1 * (sprinting ? 1.3 : 1.0) * (0.21600002 / (f * f * f)) : (sprinting ? 0.026 : 0.02);
        double impulse = in.fwd() ? (in.sneak() ? 0.3 : 1.0) : 0;
        s.vx += -Math.sin(yr) * acc * impulse;
        s.vz += Math.cos(yr) * acc * impulse;

        BlockPos feet = BlockPos.ofFloored(s.x, s.y, s.z);
        boolean climbing = w.getBlockState(feet).isIn(BlockTags.CLIMBABLE);
        if (!w.getFluidState(feet).isEmpty()) s.bad = true;
        if (climbing) {
            s.vx = Math.max(-0.15, Math.min(0.15, s.vx));
            s.vz = Math.max(-0.15, Math.min(0.15, s.vz));
            s.vy = Math.max(s.vy, in.sneak() ? 0 : -0.15);
            s.fall = 0;
        }

        Vec3d mv = new Vec3d(s.vx, s.vy, s.vz);
        if (in.sneak() && s.onGround) mv = sneakClip(w, box, mv);
        Vec3d adj = collideStep(w, box, mv, s.onGround);
        s.x += adj.x; s.y += adj.y; s.z += adj.z;

        boolean hx = adj.x != mv.x, hz = adj.z != mv.z, vColl = adj.y != mv.y;
        s.onGround = vColl && mv.y < 0;
        if (hx) s.vx = 0;
        if (hz) s.vz = 0;
        if (vColl) s.vy = 0;
        if (adj.y < 0) s.fall -= adj.y;
        if (s.onGround) { s.maxFall = Math.max(s.maxFall, s.fall); s.fall = 0; }
        if (climbing && (hx || hz || in.jump())) s.vy = 0.2;

        s.vy = (s.vy - 0.08) * 0.98;
        s.vx *= f;
        s.vz *= f;
    }

    // ---------------- one whole move ----------------
    private static void step(World w, State s, Inp in, List<Inp> tape, List<Vec3d> traj) {
        tape.add(in);
        traj.add(new Vec3d(s.x, s.y, s.z));
        tick(w, s, in);
    }

    /**
     * Start at (sx,sy,sz) at rest, face yaw, sprint forward runUp ticks, then (optionally) jump and
     * hold forward until landing, then sneak to a stop. Returns the exact key tape or null if it fails.
     */
    public static Result run(World w, double sx, double sy, double sz, float yaw, int runUp, boolean jump) {
        State s = new State(sx, sy, sz);
        Inp none = new Inp(yaw, false, false, false, false);
        for (int i = 0; i < 8 && !s.onGround; i++) tick(w, s, none);
        if (!s.onGround || s.y < sy - 0.7 || s.bad) return null;
        s.maxFall = 0; s.fall = 0;
        Vec3d start = new Vec3d(sx, s.y, sz);
        BlockPos startBlock = BlockPos.ofFloored(sx, s.y + 0.01, sz);

        List<Inp> tape = new ArrayList<>();
        List<Vec3d> traj = new ArrayList<>();
        Inp run = new Inp(yaw, true, false, false, true);
        for (int i = 0; i < runUp; i++) { step(w, s, run, tape, traj); if (s.bad) return null; }
        if (jump) step(w, s, new Inp(yaw, true, true, false, true), tape, traj);

        int guard = 0;
        Inp air = new Inp(yaw, jump, false, false, jump);
        while (!s.onGround && guard++ < 80) {
            step(w, s, air, tape, traj);
            if (s.bad || s.maxFall > 3.0 || s.fall > 3.0 || s.y < sy - 6) return null;
        }
        if (!s.onGround) return null;

        Inp brake = new Inp(yaw, false, false, true, false);
        for (int i = 0; i < 20 && !(s.onGround && s.speed2() < 1e-5); i++) {
            step(w, s, brake, tape, traj);
            if (s.bad) return null;
        }
        if (!s.onGround || s.maxFall > 3.0) return null;

        BlockPos end = BlockPos.ofFloored(s.x, s.y + 0.01, s.z);
        if (end.equals(startBlock)) return null;
        return new Result(start, new Vec3d(s.x, s.y, s.z), tape, traj, end);
    }
}
