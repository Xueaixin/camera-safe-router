package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.List;

public record RouteLeg(
        double distanceMeters,
        long durationMillis,
        List<Wgs84Coordinate> geometry) {

    public RouteLeg {
        geometry = List.copyOf(geometry);
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("route leg distance and duration must be non-negative");
        }
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("route leg geometry requires at least two points");
        }
    }
}
