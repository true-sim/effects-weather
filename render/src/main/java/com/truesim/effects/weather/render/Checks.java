package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.render.SceneView;

/** Argument and building checks. */
final class Checks {

    private Checks() {
    }

    /** Fails unless {@code g} builds {@code view}'s port: a view's technique belongs to its view. */
    @AllowAllocation("failing")
    static void inView(GraphBuilder g, SceneView view, Object technique) {
        if (g.view() != view.port()) {
            throw new IllegalStateException(technique.getClass().getSimpleName() + " of " + view.port()
                + " was added to " + (g.view() == null ? "the frame" : g.view()) + ": add it to its view");
        }
    }
}
