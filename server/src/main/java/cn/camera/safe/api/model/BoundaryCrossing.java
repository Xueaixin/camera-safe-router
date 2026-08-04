package cn.camera.safe.api.model;

import java.util.Objects;

public record BoundaryCrossing(
        String portalId,
        String roadName,
        BoundaryDirection direction,
        BoundaryRole boundaryRole,
        OutputCoordinate wgs84,
        OutputCoordinate gcj02) {

    public BoundaryCrossing {
        if (portalId == null || portalId.isBlank()) {
            throw new IllegalArgumentException("portal id is required");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(boundaryRole, "boundaryRole");
        Objects.requireNonNull(wgs84, "wgs84");
        Objects.requireNonNull(gcj02, "gcj02");
    }
}
