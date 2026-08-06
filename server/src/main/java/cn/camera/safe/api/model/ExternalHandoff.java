package cn.camera.safe.api.model;

import java.util.Objects;

public record ExternalHandoff(
        OutputCoordinate wgs84,
        OutputCoordinate gcj02,
        double boundaryClearanceMeters,
        int poiSearchRadiusMeters) {

    public ExternalHandoff {
        Objects.requireNonNull(wgs84, "wgs84");
        Objects.requireNonNull(gcj02, "gcj02");
        if (!Double.isFinite(boundaryClearanceMeters) || boundaryClearanceMeters < 0) {
            throw new IllegalArgumentException("boundary clearance must be non-negative");
        }
        if (poiSearchRadiusMeters < 0) {
            throw new IllegalArgumentException("POI search radius must be non-negative");
        }
    }
}
