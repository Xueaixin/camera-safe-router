package cn.camera.safe.api.model;

public record HealthResponse(String status) {
    public HealthResponse {
        if (!"UP".equals(status)) {
            throw new IllegalArgumentException("health status must be UP");
        }
    }
}
