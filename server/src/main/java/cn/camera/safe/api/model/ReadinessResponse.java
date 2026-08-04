package cn.camera.safe.api.model;

public record ReadinessResponse(
        String status,
        boolean graphLoaded,
        boolean sixthRingTopologyLoaded,
        boolean cameraSnapshotLoaded,
        boolean blockedEdgesLoaded,
        String reason) {
}
