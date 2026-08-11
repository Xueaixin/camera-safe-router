package cn.camera.safe.api.model;

import cn.camera.safe.coordinate.CoordinateSystem;

import java.util.Objects;

public record ControlledAreaResponse(
        String boundaryVersion,
        CoordinateSystem coordinateSystem,
        ControlledAreaGeometry geometry,
        boolean approvedForProduction,
        double cameraOutsideMarginMeters) {

    public ControlledAreaResponse {
        if (boundaryVersion == null || boundaryVersion.isBlank()) {
            throw new IllegalArgumentException("boundary version is required");
        }
        Objects.requireNonNull(coordinateSystem, "coordinateSystem");
        Objects.requireNonNull(geometry, "geometry");
        if (!Double.isFinite(cameraOutsideMarginMeters) || cameraOutsideMarginMeters < 0) {
            throw new IllegalArgumentException("camera outside margin must be non-negative");
        }
    }
}
