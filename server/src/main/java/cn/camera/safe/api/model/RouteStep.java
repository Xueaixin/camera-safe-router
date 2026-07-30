package cn.camera.safe.api.model;

public record RouteStep(
        String instruction,
        String roadName,
        double distanceMeters,
        long durationSeconds,
        int startIndex,
        int endIndex) {
}
