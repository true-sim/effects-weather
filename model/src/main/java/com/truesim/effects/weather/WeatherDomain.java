package com.truesim.effects.weather;

import com.truesim.effects.atmosphere.ephemeris.Planet;

/**
 * The world the weather covers: a flat world (x east, y up, z south, the ground at y = 0) or a planet (centred at the
 * origin, its pole on +y, spherical with the planet's equatorial radius; altitudes above that sphere). The planet is
 * what makes the weather global, up to orbit: the far map covers it whole.
 *
 * <p>The weather maps near the camera are flat grids in a <i>plane</i>: on a flat world the ground itself; on a planet
 * the tangent plane at an anchor near the camera, mapped to the sphere gnomonically (the plane point (x, z) lies on the
 * ray from the centre through anchor + x·east − z·north), re-anchored when the camera has moved far
 * ({@link #reanchor}). Allocation-free.
 */
public final class WeatherDomain {

    private final boolean sphere;
    private final double[] scratch = new double[9];
    private final double radius;
    // The plane's anchor frame: up (anchor direction), east, north; on a flat world the identity.
    private double ux;
    private double uy = 1;
    private double uz;
    private double ex = 1;
    private double ey;
    private double ez;
    private double nx;
    private double ny;
    private double nz = -1;

    private WeatherDomain(boolean sphere, double radius) {
        this.sphere = sphere;
        this.radius = radius;
    }

    /** A flat world. */
    public static WeatherDomain flat() {
        return new WeatherDomain(false, Double.POSITIVE_INFINITY);
    }

    /** A spherical planet of {@code planet}'s equatorial radius, anchored at its north pole until re-anchored. */
    public static WeatherDomain sphere(Planet planet) {
        WeatherDomain d = new WeatherDomain(true, planet.equatorialRadius());
        d.reanchor(0, planet.equatorialRadius(), 0);
        return d;
    }

    public boolean isSphere() {
        return sphere;
    }

    /** The planet's radius in metres; infinite on a flat world. */
    public double radius() {
        return radius;
    }

    /** The height of (x, y, z) above the ground. */
    public double altitude(double x, double y, double z) {
        return sphere ? Math.sqrt(x * x + y * y + z * z) - radius : y;
    }

    /** Writes the ground point under (x, y, z) at {@code out[o..o+2]}. */
    public void surface(double x, double y, double z, double[] out, int o) {
        if (!sphere) {
            out[o] = x;
            out[o + 1] = 0;
            out[o + 2] = z;
            return;
        }
        double k = radius / Math.max(Math.sqrt(x * x + y * y + z * z), 1e-9);
        out[o] = x * k;
        out[o + 1] = y * k;
        out[o + 2] = z * k;
    }

    /**
     * Writes the local frame at ground point (x, y, z): east at {@code out[0..2]}, north at {@code out[3..5]}, up at
     * {@code out[6..8]}. At the poles east is +x.
     */
    public void frame(double x, double y, double z, double[] out) {
        if (!sphere) {
            out[0] = 1;
            out[1] = 0;
            out[2] = 0;
            out[3] = 0;
            out[4] = 0;
            out[5] = -1;
            out[6] = 0;
            out[7] = 1;
            out[8] = 0;
            return;
        }
        double len = Math.sqrt(x * x + y * y + z * z);
        double upx = x / len;
        double upy = y / len;
        double upz = z / len;
        // east = (+y) × up, normalised; north = up × east.
        double e0 = upz;
        double e2 = -upx;
        double el = Math.sqrt(e0 * e0 + e2 * e2);
        if (el < 1e-12) {
            e0 = 1;
            e2 = 0;
            el = 1;
        }
        e0 /= el;
        e2 /= el;
        out[0] = e0;
        out[1] = 0;
        out[2] = e2;
        out[3] = upy * e2;
        out[4] = upz * e0 - upx * e2;
        out[5] = -upy * e0;
        out[6] = upx;
        out[7] = upy;
        out[8] = upz;
    }

    /**
     * Moves ground point {@code p[o..o+2]} by (east, north) metres along the ground, in place: on a planet along the
     * tangent, back onto the sphere.
     */
    public void move(double[] p, int o, double east, double north, double[] scratch) {
        frame(p[o], p[o + 1], p[o + 2], scratch);
        double x = p[o] + east * scratch[0] + north * scratch[3];
        double y = p[o + 1] + east * scratch[1] + north * scratch[4];
        double z = p[o + 2] + east * scratch[2] + north * scratch[5];
        surface(x, y, z, p, o);
    }

    /** Moves the planet's plane anchor to the ground point under (x, y, z); nothing on a flat world. */
    public void reanchor(double x, double y, double z) {
        if (!sphere) {
            return;
        }
        double[] f = scratch;
        frame(x, y, z, f);
        ex = f[0];
        ey = f[1];
        ez = f[2];
        nx = f[3];
        ny = f[4];
        nz = f[5];
        ux = f[6];
        uy = f[7];
        uz = f[8];
    }

    /** Writes the ground point of plane point (px, pz) at {@code out[o..o+2]}. */
    public void planeToGround(double px, double pz, double[] out, int o) {
        if (!sphere) {
            out[o] = px;
            out[o + 1] = 0;
            out[o + 2] = pz;
            return;
        }
        double x = ux * radius + px * ex - pz * nx;
        double y = uy * radius + px * ey - pz * ny;
        double z = uz * radius + px * ez - pz * nz;
        surface(x, y, z, out, o);
    }

    /**
     * Writes the plane point (x, z) of the ground point under (x, y, z) at {@code out[o..o+1]}; false (and nothing
     * written) for points on the planet's far side from the anchor.
     */
    public boolean groundToPlane(double x, double y, double z, double[] out, int o) {
        if (!sphere) {
            out[o] = x;
            out[o + 1] = z;
            return true;
        }
        double along = x * ux + y * uy + z * uz;
        if (along <= 0) {
            return false;
        }
        double k = radius / along;
        out[o] = (x * ex + y * ey + z * ez) * k;
        out[o + 1] = -(x * nx + y * ny + z * nz) * k;
        return true;
    }

    /** The anchor's direction (up at the anchor), component {@code axis}. */
    public double anchor(int axis) {
        return axis == 0 ? ux : axis == 1 ? uy : uz;
    }
}
