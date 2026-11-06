package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Transform;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import java.util.Objects;

/**
 * The brushes in effect, in the order they apply: direct ones the game placed (they drift with their velocity and
 * expire after their lifetime) and those on scene nodes ({@link WeatherComponents#BRUSH}), copied in by
 * {@link #syncFromScene}. Positions are ground points of the {@link WeatherDomain}. A fixed capacity; stepping
 * allocates nothing.
 */
public final class Brushes {

    private final int capacity;
    private final WeatherBrush[] brushes;
    private final double[] position;
    private final double[] age;
    private final int[] ids;
    private final long[] nodes;
    private final double[] scratch = new double[9];
    private int count;
    private int nextId = 1;

    @AllowAllocation("made once")
    public Brushes(int capacity) {
        this.capacity = Checks.range(capacity, 0, 1 << 16, "capacity");
        brushes = new WeatherBrush[capacity];
        position = new double[3 * capacity];
        age = new double[capacity];
        ids = new int[capacity];
        nodes = new long[capacity];
    }

    /** Adds {@code brush} at ground point (x, y, z); returns its id, or −1 when full. */
    public int add(WeatherBrush brush, double x, double y, double z) {
        return add(brush, x, y, z, 0L);
    }

    private int add(WeatherBrush brush, double x, double y, double z, long node) {
        Objects.requireNonNull(brush, "brush");
        if (count == capacity) {
            return -1;
        }
        brushes[count] = brush;
        position[3 * count] = x;
        position[3 * count + 1] = y;
        position[3 * count + 2] = z;
        age[count] = 0;
        ids[count] = nextId;
        nodes[count] = node;
        count++;
        return nextId++;
    }

    /** Removes the brush of {@code id}; false if there is none. */
    public boolean remove(int id) {
        for (int i = 0; i < count; i++) {
            if (ids[i] == id) {
                removeAt(i);
                return true;
            }
        }
        return false;
    }

    private void removeAt(int i) {
        int tail = count - i - 1;
        System.arraycopy(brushes, i + 1, brushes, i, tail);
        System.arraycopy(position, 3 * (i + 1), position, 3 * i, 3 * tail);
        System.arraycopy(age, i + 1, age, i, tail);
        System.arraycopy(ids, i + 1, ids, i, tail);
        System.arraycopy(nodes, i + 1, nodes, i, tail);
        count--;
        brushes[count] = null;
    }

    /** Ages the direct brushes by {@code dt}, removes the expired ones and moves them by their velocity. */
    void step(double dt, WeatherDomain domain) {
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            WeatherBrush b = brushes[i];
            if (nodes[i] == 0 && age[i] >= b.lifetime()) {
                removeAt(i);
                i--;
                continue;
            }
            if (nodes[i] == 0 && (b.velocityEast() != 0 || b.velocityNorth() != 0)) {
                domain.move(position, 3 * i, b.velocityEast() * dt, b.velocityNorth() * dt, scratch);
            }
        }
    }

    /**
     * Replaces the node brushes by the {@link WeatherComponents#BRUSH} components of {@code scene}'s nodes, placed on
     * the ground under each node's world position (as of the scene's last update), after the direct brushes. Call it
     * when brush nodes were added, moved or removed, not every step: reading transforms makes values.
     */
    @AllowAllocation("reads node transforms; when brush nodes changed, not per step")
    public void syncFromScene(SceneGraph scene, WeatherDomain domain) {
        for (int i = count - 1; i >= 0; i--) {
            if (nodes[i] != 0) {
                removeAt(i);
            }
        }
        if (!scene.components().hasStore(WeatherComponents.BRUSH)) {
            return;
        }
        ComponentStore<WeatherBrush> store = scene.components().store(WeatherComponents.BRUSH);
        double[] ground = new double[3];
        for (int i = 0; i < store.size(); i++) {
            long node = store.node(i);
            Transform world = scene.world(node);
            domain.surface(world.translation().x(), world.translation().y(), world.translation().z(), ground, 0);
            add(store.value(i), ground[0], ground[1], ground[2], node);
        }
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    /** Brush {@code i} in application order. */
    public WeatherBrush brush(int i) {
        return brushes[Objects.checkIndex(i, count)];
    }

    public int id(int i) {
        return ids[Objects.checkIndex(i, count)];
    }

    /** The node it came from, or 0 for a direct brush. */
    public long node(int i) {
        return nodes[Objects.checkIndex(i, count)];
    }

    /** Its ground point's coordinate {@code axis}. */
    public double position(int i, int axis) {
        return position[3 * Objects.checkIndex(i, count) + axis];
    }
}
