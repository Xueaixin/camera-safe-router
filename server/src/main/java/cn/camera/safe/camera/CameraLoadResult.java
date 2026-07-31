package cn.camera.safe.camera;

import java.time.Instant;
import java.util.List;

public record CameraLoadResult(
        String sourceSha256,
        Instant loadedAt,
        int sourceRecordCount,
        int retainedRecordCount,
        int outsideSixRingRecordCount,
        int unrecognizedSixRingOutRecordCount,
        List<CameraPoint> cameras,
        List<CameraValidationIssue> issues) {

    public CameraLoadResult {
        if (sourceRecordCount < 0
                || retainedRecordCount < 0
                || outsideSixRingRecordCount < 0
                || unrecognizedSixRingOutRecordCount < 0) {
            throw new IllegalArgumentException("camera record counts must not be negative");
        }
        if (sourceRecordCount != retainedRecordCount + outsideSixRingRecordCount) {
            throw new IllegalArgumentException("camera filter counts must cover all source records");
        }
        if (unrecognizedSixRingOutRecordCount > retainedRecordCount) {
            throw new IllegalArgumentException("unrecognized filter count must be retained");
        }
        cameras = List.copyOf(cameras);
        issues = List.copyOf(issues);
    }

    public boolean isValid() {
        return sourceRecordCount > 0
                && retainedRecordCount > 0
                && cameras.size() == retainedRecordCount
                && issues.isEmpty();
    }

    public int excludedRecordCount() {
        return outsideSixRingRecordCount;
    }
}
