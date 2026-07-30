package cn.camera.safe.api.model;

public record ReadinessResponse(
        String status,
        boolean graphLoaded,
        boolean cameraSnapshotLoaded,
        boolean blockedEdgesLoaded,
        String reason) {
}
