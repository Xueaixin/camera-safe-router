package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.Objects;

public record ExternalHandoffPoint(
        Wgs84Coordinate coordinate,
        double boundaryClearanceMeters,
        int poiSearchRadiusMeters) {

    public ExternalHandoffPoint {
        Objects.requireNonNull(coordinate, "coordinate");
        if (!Double.isFinite(boundaryClearanceMeters) || boundaryClearanceMeters < 0) {
            throw new IllegalArgumentException("boundary clearance must be non-negative");
        }
        if (poiSearchRadiusMeters < 0) {
            throw new IllegalArgumentException("POI search radius must be non-negative");
        }
    }
}
