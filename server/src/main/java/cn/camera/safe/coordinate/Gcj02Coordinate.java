package cn.camera.safe.coordinate;

public record Gcj02Coordinate(double lng, double lat) {
    public Gcj02Coordinate {
        CoordinateValidator.requireValid(lng, lat);
    }
}
