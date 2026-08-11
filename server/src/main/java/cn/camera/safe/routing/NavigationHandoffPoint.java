package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.Objects;

public record NavigationHandoffPoint(
        Wgs84Coordinate coordinate,
        double boundaryClearanceMeters,
        String roadName,
        Type type,
        Segment segment,
        int geometryIndex,
        double fractionFromPrevious) {

    public enum Type {
        ORDINARY_ROAD,
        HIGHWAY
    }

    public enum Segment {
        SAFE,
        REFERENCE
    }

    public NavigationHandoffPoint {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(segment, "segment");
        if (!Double.isFinite(boundaryClearanceMeters) || boundaryClearanceMeters <= 0) {
            throw new IllegalArgumentException("boundary clearance must be positive");
        }
        if (geometryIndex <= 0) {
            throw new IllegalArgumentException("navigation handoff must be inside its route segment");
        }
        if (!Double.isFinite(fractionFromPrevious)
                || fractionFromPrevious <= 0 || fractionFromPrevious > 1) {
            throw new IllegalArgumentException("handoff segment fraction must be in (0, 1]");
        }
        roadName = roadName == null ? "" : roadName;
    }

    public NavigationHandoffPoint(
            Wgs84Coordinate coordinate,
            double boundaryClearanceMeters,
            String roadName,
            Segment segment,
            int geometryIndex) {
        this(coordinate, boundaryClearanceMeters, roadName,
                Type.ORDINARY_ROAD, segment, geometryIndex, 1);
    }
}
