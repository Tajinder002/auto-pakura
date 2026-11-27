package dev.autoparkour;

/** One tick of a run: where the player was, where they looked, which keys were held. */
public record Frame(double x, double y, double z, float yaw, float pitch,
                    boolean fwd, boolean back, boolean left, boolean right,
                    boolean jump, boolean sneak, boolean sprint) {

    public double distTo(double px, double py, double pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
