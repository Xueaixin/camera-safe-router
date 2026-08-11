package cn.camera.safe.api.model;

import java.util.Objects;

public record NavigationHandoff(
        OutputCoordinate wgs84,
        OutputCoordinate gcj02,
        double boundaryClearanceMeters,
        String roadName,
        NavigationHandoffType type) {

    public NavigationHandoff {
        Objects.requireNonNull(wgs84, "wgs84");
        Objects.requireNonNull(gcj02, "gcj02");
        Objects.requireNonNull(type, "type");
        if (!Double.isFinite(boundaryClearanceMeters) || boundaryClearanceMeters <= 0) {
            throw new IllegalArgumentException("boundary clearance must be positive");
        }
        roadName = roadName == null || roadName.isBlank() ? null : roadName;
    }

    public NavigationHandoff(
            OutputCoordinate wgs84,
            OutputCoordinate gcj02,
            double boundaryClearanceMeters,
            String roadName) {
        this(wgs84, gcj02, boundaryClearanceMeters, roadName,
                NavigationHandoffType.ORDINARY_ROAD);
    }
}
