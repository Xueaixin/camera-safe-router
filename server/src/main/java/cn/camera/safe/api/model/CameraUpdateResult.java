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
        int matchedCameraCount,
        int unmatchedCameraCount,
        int blockedEdgeCount,
        Instant completedAt) {
}
