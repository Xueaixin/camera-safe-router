package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.Objects;

public record NavigationHandoffPoint(
        Wgs84Coordinate coordinate,
        double boundaryClearanceMeters,
        String roadName,
        Segment segment,
        int geometryIndex) {

    public enum Segment {
        SAFE,
        REFERENCE
    }

    public NavigationHandoffPoint {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(segment, "segment");
        if (!Double.isFinite(boundaryClearanceMeters) || boundaryClearanceMeters <= 0) {
            throw new IllegalArgumentException("boundary clearance must be positive");
        }
        if (geometryIndex <= 0) {
            throw new IllegalArgumentException("navigation handoff must be inside its route segment");
        }
        roadName = roadName == null ? "" : roadName;
    }
}
