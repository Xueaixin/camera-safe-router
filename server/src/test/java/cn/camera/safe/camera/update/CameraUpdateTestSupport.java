package cn.camera.safe.camera.update;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.RoutingSnapshot;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;

public final class CameraUpdateTestSupport {
    private CameraUpdateTestSupport() {
    }

    public static AppProperties properties(Path root, String sourceUrl) {
        AppProperties.Update update = new AppProperties.Update(
                true,
                sourceUrl,
                "0 15 3 * * *",
                "Asia/Shanghai",
                root.resolve("downloads").toString(),
                root.resolve("failed").toString(),
                root.resolve("backups").toString(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                1024 * 1024,
                1,
                0.5,
                0.9,
                0.5,
                2,
                2,
                2);
        return new AppProperties(
                new AppProperties.Routing(
                        "pbf", "cache", "candidate-cache", RoutingProfileMode.CURRENT,
                        30, 1, 1,
                        Duration.ofSeconds(2), 10_000),
                new AppProperties.Cameras(
                        root.resolve("cameras/camera.json").toString(),
                        root.resolve("snapshots").toString(),
                        true,
                        100,
                        1,
                        update),
                new AppProperties.Admin(true));
    }

    public static RoutingSnapshot snapshot(String sourceHash, int matchedCameraCount, int blockedEdgeCount) {
        Wgs84Coordinate wgs84 = new Wgs84Coordinate(116.4, 39.9);
        CameraPoint point = new CameraPoint(
                "camera-1", "district", new Gcj02Coordinate(116.4, 39.9), wgs84,
                "address", "type", null);
        CameraSnapshot cameraSnapshot = new CameraSnapshot(
                "sha256:" + sourceHash + "@" + Instant.EPOCH,
                sourceHash,
                Instant.EPOCH,
                1,
                1,
                0,
                0,
                List.of(point));
        BitSet edges = new BitSet();
        for (int edgeId = 0; edgeId < blockedEdgeCount; edgeId++) {
            edges.set(edgeId);
        }
        BlockedEdgeSnapshot blocked = new BlockedEdgeSnapshot(
                edges, edges, cameraSnapshot.version(), "sha256:" + sourceHash);
        return new RoutingSnapshot(
                cameraSnapshot,
                new CameraSpatialIndex(cameraSnapshot.cameras()),
                blocked,
                "graph-v1",
                30,
                matchedCameraCount,
                matchedCameraCount == 0 ? List.of("camera-1") : List.of());
    }
}
