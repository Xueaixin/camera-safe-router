package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.Objects;
import java.util.Optional;

/** Internal target where the continuous controlled-road prefix ends. */
record ControlReleasePoint(
        String id,
        int edgeId,
        int edgeKey,
        SixthRingPortal.Direction direction,
        Type type,
        double fractionFromBase,
        Wgs84Coordinate coordinate,
        String roadName,
        SixthRingPortal physicalPortal) {

    ControlReleasePoint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("control release id is required");
        }
        if (edgeId < 0 || edgeKey < 0) {
            throw new IllegalArgumentException("control release edge identifiers must be non-negative");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(coordinate, "coordinate");
        if (!Double.isFinite(fractionFromBase)
                || fractionFromBase < 0 || fractionFromBase > 1) {
            throw new IllegalArgumentException("control release fraction must be within [0, 1]");
        }
        if ((type == Type.ORDINARY_BOUNDARY) != (physicalPortal != null)) {
            throw new IllegalArgumentException(
                    "only ordinary boundary releases carry a physical portal");
        }
        roadName = roadName == null ? "" : roadName;
    }

    Optional<SixthRingPortal> physicalPortalOptional() {
        return Optional.ofNullable(physicalPortal);
    }

    enum Type {
        ORDINARY_BOUNDARY
    }
}
