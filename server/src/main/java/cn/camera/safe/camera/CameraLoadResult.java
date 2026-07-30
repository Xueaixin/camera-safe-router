package cn.camera.safe.camera;

import java.time.Instant;
import java.util.List;

public record CameraLoadResult(
        String sourceSha256,
        Instant loadedAt,
        int sourceRecordCount,
        List<CameraPoint> cameras,
        List<CameraValidationIssue> issues) {

    public CameraLoadResult {
        cameras = List.copyOf(cameras);
        issues = List.copyOf(issues);
    }

    public boolean isValid() {
        return sourceRecordCount > 0 && cameras.size() == sourceRecordCount && issues.isEmpty();
    }
}
