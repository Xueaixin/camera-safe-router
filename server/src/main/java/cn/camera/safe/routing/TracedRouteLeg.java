package cn.camera.safe.routing;

import java.util.List;
import java.util.Objects;

record TracedRouteLeg(
        RouteLeg leg,
        List<RouteTracePoint> trace) {

    TracedRouteLeg {
        Objects.requireNonNull(leg, "leg");
        trace = List.copyOf(trace);
    }
}
