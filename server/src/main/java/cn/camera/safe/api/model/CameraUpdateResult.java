package cn.camera.safe.api.model;

import java.time.Instant;

public record CameraUpdateResult(
        CameraUpdateStatus status,
        String sourceSha256,
        String previousSourceSha256,
        int sourceRecordCount,
        int retainedRecordCount,
        int excludedRecordCount,
        int unrecognizedIsSixRingOutCount,
        int restrictedCameraCount,
        int outsideControlAreaCameraCount,
        double cameraOutsideMarginMeters,
        String controlBoundaryVersion,
        int matchedCameraCount,
        int unmatchedCameraCount,
        int blockedEdgeCount,
        Instant completedAt) {

    public CameraUpdateResult(
            CameraUpdateStatus status,
            String sourceSha256,
            String previousSourceSha256,
            int sourceRecordCount,
            int retainedRecordCount,
            int excludedRecordCount,
            int unrecognizedIsSixRingOutCount,
            int matchedCameraCount,
            int unmatchedCameraCount,
            int blockedEdgeCount,
            Instant completedAt) {
        this(status, sourceSha256, previousSourceSha256,
                sourceRecordCount, retainedRecordCount, excludedRecordCount,
                unrecognizedIsSixRingOutCount, retainedRecordCount, 0,
                0, "legacy-unscoped", matchedCameraCount, unmatchedCameraCount,
                blockedEdgeCount, completedAt);
    }
}
