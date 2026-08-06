package cn.camera.safe.routing;

import java.util.List;

record PortalAnchoredRoute(
        EngineRoute route,
        List<RouteTracePoint> trace) {

    PortalAnchoredRoute {
        trace = List.copyOf(trace);
    }
}
