package cn.camera.safe.api.model;

import cn.camera.safe.coordinate.CoordinateSystem;

import java.util.List;

public record RouteResponse(
        String routeId,
        CoordinateSystem coordinateSystem,
        double distanceMeters,
        long durationSeconds,
        int cameraConflictCount,
        String cameraSnapshotVersion,
        String blockedEdgeVersion,
        List<OutputCoordinate> geometry,
        List<RouteStep> steps) {

    public RouteResponse {
        geometry = List.copyOf(geometry);
        steps = List.copyOf(steps);
        if (cameraConflictCount != 0) {
            throw new IllegalArgumentException("successful route response must have zero camera conflicts");
        }
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("route response geometry requires at least two points");
        }
    }
}
