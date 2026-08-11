package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.List;

public record EngineRoute(
        double distanceMeters,
        long durationMillis,
        List<Wgs84Coordinate> geometry,
        List<RouteTracePoint> trace,
        long searchEdgeChecks,
        long virtualEdgeChecks,
        long blockedRejections) {

    public EngineRoute {
        geometry = List.copyOf(geometry);
        trace = List.copyOf(trace);
    }

    public EngineRoute(
            double distanceMeters,
            long durationMillis,
            List<Wgs84Coordinate> geometry,
            long searchEdgeChecks,
            long virtualEdgeChecks,
            long blockedRejections) {
        this(distanceMeters, durationMillis, geometry, List.of(), searchEdgeChecks,
                virtualEdgeChecks, blockedRejections);
    }
}
