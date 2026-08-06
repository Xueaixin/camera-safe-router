package cn.camera.safe.api.model;

import java.util.Objects;

public record NavigationHandoff(
        OutputCoordinate wgs84,
        OutputCoordinate gcj02,
        double boundaryClearanceMeters,
        String roadName) {

    public NavigationHandoff {
        Objects.requireNonNull(wgs84, "wgs84");
        Objects.requireNonNull(gcj02, "gcj02");
        if (!Double.isFinite(boundaryClearanceMeters) || boundaryClearanceMeters <= 0) {
            throw new IllegalArgumentException("boundary clearance must be positive");
        }
        roadName = roadName == null || roadName.isBlank() ? null : roadName;
    }
}
