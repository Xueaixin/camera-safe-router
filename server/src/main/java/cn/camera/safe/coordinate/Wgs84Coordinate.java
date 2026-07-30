package cn.camera.safe.coordinate;

public record Wgs84Coordinate(double lng, double lat) {
    public Wgs84Coordinate {
        CoordinateValidator.requireValid(lng, lat);
    }
}
