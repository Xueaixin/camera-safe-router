package cn.camera.safe.camera;

import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.Objects;

public record CameraPoint(
        String id,
        String district,
        Gcj02Coordinate gcj02,
        Wgs84Coordinate wgs84,
        String address,
        String cameraType,
        String directionText) {

    public CameraPoint {
        id = requireText(id, "id");
        district = district == null ? "" : district;
        Objects.requireNonNull(gcj02, "gcj02");
        Objects.requireNonNull(wgs84, "wgs84");
        address = requireText(address, "address");
        cameraType = requireText(cameraType, "cameraType");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
