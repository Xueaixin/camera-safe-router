package cn.camera.safe.api.model;

import java.util.List;

public record RouteSegment(
        double distanceMeters,
        long durationSeconds,
        List<OutputCoordinate> geometry) {

    public RouteSegment {
        geometry = List.copyOf(geometry);
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || durationSeconds < 0) {
            throw new IllegalArgumentException("route segment distance and duration must be non-negative");
        }
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("route segment geometry requires at least two points");
        }
    }
}
