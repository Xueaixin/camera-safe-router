package cn.camera.safe.api.model;

import java.time.Instant;

public record CameraSnapshotStatus(
        String status,
        String snapshotVersion,
        String blockedEdgeVersion,
        int cameraCount,
        int sourceCameraCount,
        int outsideControlAreaCameraCount,
        double cameraOutsideMarginMeters,
        String controlBoundaryVersion,
        double safetyRadiusMeters,
        Instant loadedAt) {
    public CameraSnapshotStatus {
        if (!"READY".equals(status)) {
            throw new IllegalArgumentException("snapshot status must be READY");
        }
    }
}
