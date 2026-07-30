package cn.camera.safe.camera;

import java.time.Instant;
import java.util.List;

public record CameraSnapshot(
        String version,
        String sourceSha256,
        Instant loadedAt,
        List<CameraPoint> cameras) {

    public CameraSnapshot {
        cameras = List.copyOf(cameras);
        if (cameras.isEmpty()) {
            throw new IllegalArgumentException("camera snapshot must not be empty");
        }
    }

    public static CameraSnapshot from(CameraLoadResult result) {
        if (!result.isValid()) {
            throw new IllegalArgumentException("camera load result contains invalid records");
        }
        return new CameraSnapshot(
                "sha256:" + result.sourceSha256() + "@" + result.loadedAt(),
                result.sourceSha256(),
                result.loadedAt(),
                result.cameras());
    }
}
