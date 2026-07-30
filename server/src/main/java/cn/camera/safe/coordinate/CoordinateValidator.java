package cn.camera.safe.coordinate;

public final class CoordinateValidator {
    private CoordinateValidator() {
    }

    public static void requireValid(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be finite and between -180 and 180");
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be finite and between -90 and 90");
        }
    }
}
