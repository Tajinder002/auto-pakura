package dev.autoparkour;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/** A* over standing positions. Each edge is a physics-checked move from Sim. Runs in small time slices. */
public final class Planner {
    public static final class Act {
        public final BlockPos from, to;
        public final Vec3d start, endPos;
        public final List<Sim.Inp> tape;
        public final List<Vec3d> traj;
        public final String key;
        Act(BlockPos from, Sim.Result r, String key) {
            this.from = from; this.to = r.end; this.start = r.start; this.endPos = r.endPos;
            this.tape = r.tape; this.traj = r.traj; this.key = key;
        }
    }

    private static final class N {
        final BlockPos pos; final double g, f; final N parent; final Act act;
        N(BlockPos pos, double g, double f, N parent, Act act) { this.pos = pos; this.g = g; this.f = f; this.parent = parent; this.act = act; }
    }

    private static final int MAX_NODES = 2500;
    private static final double MAX_TURN = 120;
    private static final int[] RUNUPS = {0, 2, 4};
    private static final double[] OFFSETS = {-0.3, 0.0};

    private final World w;
    private final BlockPos target;
    public final double margin;      // how far off its start point a jump must still work (0 = exact only)
    private final PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
    private final Map<Long, Double> best = new HashMap<>();
    public boolean done, failed;
    public int expanded;
    public final List<Act> path = new ArrayList<>();

    public Planner(World w, BlockPos start, BlockPos target, double margin) {
        this.w = w; this.target = target; this.margin = margin;
        open.add(new N(start, 0, h(start), null, null));
        best.put(start.asLong(), 0.0);
    }

    private double h(BlockPos p) {
        double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
        double dy = target.getY() + 1 - p.getY();
        return 1.2 * (Math.hypot(dx, dz) / 0.30 + (dy > 0 ? dy * 6 : 0));
    }

    private boolean isGoal(BlockPos p) {
        return p.getX() == target.getX() && p.getZ() == target.getZ()
                && (p.getY() == target.getY() + 1 || p.getY() == target.getY());
    }

    public void step(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        while (!done && !failed && System.nanoTime() < end) {
            N n = open.poll();
            if (n == null) { failed = true; return; }
            if (best.getOrDefault(n.pos.asLong(), Double.MAX_VALUE) < n.g) continue;
            if (isGoal(n.pos)) {
                for (N c = n; c.parent != null; c = c.parent) path.add(0, c.act);
                done = true; return;
            }
            if (++expanded > MAX_NODES) { failed = true; return; }
            expand(n);
        }
    }

    /** Same move from 4 slightly shifted start points must still work (and, for jumps, land on the same block). */
    private boolean robust(BlockPos from, double cx, double cz, double dx, double dz, double off,
                           float yaw, int ru, boolean jump, BlockPos end) {
        if (margin <= 0) return true;
        double m = margin, px = -dz, pz = dx;
        double[][] d = {{dx * m, dz * m}, {-dx * m, -dz * m}, {px * m, pz * m}, {-px * m, -pz * m}};
        for (double[] o : d) {
            Sim.Result r = Sim.run(w, cx + dx * off + o[0], from.getY(), cz + dz * off + o[1], yaw, ru, jump);
            if (r == null) return false;
            if (jump && !r.end.equals(end)) return false;
        }
        return true;
    }

    private void expand(N n) {
        double cx = n.pos.getX() + 0.5, cz = n.pos.getZ() + 0.5;
        float bearing = (float) Math.toDegrees(Math.atan2(-(target.getX() + 0.5 - cx), target.getZ() + 0.5 - cz));
        for (int k = 0; k < 16; k++) {
            float yaw = MathHelper.wrapDegrees(k * 22.5f);
            if (Math.abs(MathHelper.wrapDegrees(yaw - bearing)) > MAX_TURN) continue;
            double dx = -Math.sin(Math.toRadians(yaw)), dz = Math.cos(Math.toRadians(yaw));
            for (double off : OFFSETS) {
                for (int runUp : RUNUPS) {
                    for (int jump = 0; jump < 2; jump++) {
                        if (jump == 0 && (off != 0.0 || runUp == 2)) continue;
                        int ru = (jump == 0) ? (runUp == 0 ? 3 : 8) : runUp;
                        Sim.Result r = Sim.run(w, cx + dx * off, n.pos.getY(), cz + dz * off, yaw, ru, jump == 1);
                        if (r == null) continue;
                        String key = Learn.key(n.pos, yaw, ru, jump == 1, off);
                        if (Learn.banned(key)) continue;
                        double g2 = n.g + r.tape.size() + 4 + Learn.penalty(key);
                        long ek = r.end.asLong();
                        if (best.getOrDefault(ek, Double.MAX_VALUE) <= g2) continue;
                        if (!robust(n.pos, cx, cz, dx, dz, off, yaw, ru, jump == 1, r.end)) continue;
                        best.put(ek, g2);
                        open.add(new N(r.end, g2, g2 + h(r.end), n, new Act(n.pos, r, key)));
                    }
                }
            }
        }
    }
}
