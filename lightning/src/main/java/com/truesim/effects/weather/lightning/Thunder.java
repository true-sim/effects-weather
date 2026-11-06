package com.truesim.effects.weather.lightning;

/**
 * When thunder is heard: sound leaves every point of the channel at once, so the first sound arrives from the
 * channel's nearest point and the rumble lasts until sound from its farthest point arrives. With a bolt's geometry the
 * channel is its polyline; without one, the segment from the flash's origin to its end.
 */
public final class Thunder {

    private Thunder() {
    }

    /** The speed of sound in dry air, m/s: 331.3·√(1 + T/273.15) for T in °C. */
    public static double speedOfSound(double celsius) {
        return 331.3 * Math.sqrt(1.0 + celsius / 273.15);
    }

    /** Seconds sound needs for {@code metres}. */
    public static double delay(double metres, LightningConfig config) {
        return metres / config.speedOfSound();
    }

    /** The distance from (px, py, pz) to the segment a–b. */
    public static double segmentDistance(double ax, double ay, double az, double bx, double by, double bz, double px,
        double py, double pz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double len2 = dx * dx + dy * dy + dz * dz;
        double t = len2 > 0 ? Math.clamp(((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / len2, 0.0, 1.0) : 0.0;
        double cx = ax + t * dx - px;
        double cy = ay + t * dy - py;
        double cz = az + t * dz - pz;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private static double pointDistance(double ax, double ay, double az, double px, double py, double pz) {
        double dx = ax - px;
        double dy = ay - py;
        double dz = az - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The nearest distance from the listener to the event's origin–end segment. */
    public static double nearest(LightningEvents events, int slot, double px, double py, double pz) {
        return segmentDistance(events.origin(slot, 0), events.origin(slot, 1), events.origin(slot, 2),
            events.end(slot, 0), events.end(slot, 1), events.end(slot, 2), px, py, pz);
    }

    /** The farthest distance from the listener to the event's origin–end segment (one of its ends). */
    public static double farthest(LightningEvents events, int slot, double px, double py, double pz) {
        return Math.max(pointDistance(events.origin(slot, 0), events.origin(slot, 1), events.origin(slot, 2), px, py, pz),
            pointDistance(events.end(slot, 0), events.end(slot, 1), events.end(slot, 2), px, py, pz));
    }

    /** The nearest distance from the listener to any segment of {@code bolt}. */
    public static double nearest(BoltGeometry bolt, double px, double py, double pz) {
        double best = Double.POSITIVE_INFINITY;
        for (int s = 0; s < bolt.segmentCount(); s++) {
            int a = bolt.segmentStart(s);
            int b = bolt.segmentEnd(s);
            best = Math.min(best, segmentDistance(bolt.x(a), bolt.y(a), bolt.z(a), bolt.x(b), bolt.y(b), bolt.z(b), px,
                py, pz));
        }
        return best;
    }

    /** The farthest distance from the listener to any point of {@code bolt}. */
    public static double farthest(BoltGeometry bolt, double px, double py, double pz) {
        double worst = 0;
        for (int i = 0; i < bolt.pointCount(); i++) {
            worst = Math.max(worst, pointDistance(bolt.x(i), bolt.y(i), bolt.z(i), px, py, pz));
        }
        return worst;
    }
}
