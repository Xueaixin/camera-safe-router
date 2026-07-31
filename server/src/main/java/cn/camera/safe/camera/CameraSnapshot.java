package cn.camera.safe.camera;

import java.time.Instant;
import java.util.List;

public record CameraSnapshot(
        String version,
        String sourceSha256,
        Instant loadedAt,
        int sourceRecordCount,
        int retainedRecordCount,
        int outsideSixRingRecordCount,
        int unrecognizedSixRingOutRecordCount,
        List<CameraPoint> cameras) {

    public CameraSnapshot {
        cameras = List.copyOf(cameras);
        if (cameras.isEmpty()) {
            throw new IllegalArgumentException("camera snapshot must not be empty");
        }
        if (retainedRecordCount != cameras.size()) {
            throw new IllegalArgumentException("retained camera count must match snapshot cameras");
        }
        if (sourceRecordCount != retainedRecordCount + outsideSixRingRecordCount) {
            throw new IllegalArgumentException("camera snapshot counts must cover all source records");
        }
        if (unrecognizedSixRingOutRecordCount > retainedRecordCount) {
            throw new IllegalArgumentException("unrecognized filter count must be retained");
        }
    }

    public CameraSnapshot(
            String version,
            String sourceSha256,
            Instant loadedAt,
            List<CameraPoint> cameras) {
        this(version, sourceSha256, loadedAt,
                cameras.size(), cameras.size(), 0, 0, cameras);
    }

    public static CameraSnapshot from(CameraLoadResult result) {
        if (!result.isValid()) {
            throw new IllegalArgumentException("camera load result contains invalid records");
        }
        return new CameraSnapshot(
                "sha256:" + result.sourceSha256() + "@" + result.loadedAt(),
                result.sourceSha256(),
                result.loadedAt(),
                result.sourceRecordCount(),
                result.retainedRecordCount(),
                result.outsideSixRingRecordCount(),
                result.unrecognizedSixRingOutRecordCount(),
                result.cameras());
    }
}
