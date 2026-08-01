package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.camera.CameraLoadResult;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Component
public final class RoutingSnapshotBuilder {
    private final AppProperties properties;
    private final GraphHopperManager graphManager;
    private final CameraJsonLoader cameraLoader;
    private final BlockedEdgeGenerator blockedEdgeGenerator;

    public RoutingSnapshotBuilder(
            AppProperties properties,
            GraphHopperManager graphManager,
            CameraJsonLoader cameraLoader,
            BlockedEdgeGenerator blockedEdgeGenerator) {
        this.properties = properties;
        this.graphManager = graphManager;
        this.cameraLoader = cameraLoader;
        this.blockedEdgeGenerator = blockedEdgeGenerator;
    }

    public RoutingSnapshot build() {
        return build(Path.of(properties.cameras().jsonPath()));
    }

    public RoutingSnapshot build(Path cameraPath) {
        if (!properties.cameras().sourceCoordinateVerified()) {
            throw new SnapshotBuildException(
                    "camera source coordinate system has not been manually verified");
        }
        if (!graphManager.isReady()) {
            throw new SnapshotBuildException("routing graph is not ready");
        }
        try {
            CameraLoadResult loaded = cameraLoader.load(cameraPath);
            if (!loaded.isValid()) {
                String firstIssue = loaded.issues().isEmpty()
                        ? "no records"
                        : loaded.issues().getFirst().reason();
                throw new SnapshotBuildException("camera JSON validation failed: valid="
                        + loaded.cameras().size() + " retained=" + loaded.retainedRecordCount()
                        + " source=" + loaded.sourceRecordCount()
                        + " outsideSixRing=" + loaded.outsideSixRingRecordCount()
                        + " unrecognizedIsSixRingOut=" + loaded.unrecognizedSixRingOutRecordCount()
                        + " issues=" + loaded.issues().size() + " firstIssue=" + firstIssue);
            }
            CameraSnapshot cameraSnapshot = CameraSnapshot.from(loaded);
            CameraSpatialIndex cameraIndex = new CameraSpatialIndex(cameraSnapshot.cameras());
            BlockedEdgeBuildResult blocked = blockedEdgeGenerator.generate(
                    cameraSnapshot,
                    graphManager.requireRoadEdgeIndex(),
                    properties.routing().safetyRadiusMeters());
            return new RoutingSnapshot(
                    cameraSnapshot,
                    cameraIndex,
                    blocked.snapshot(),
                    graphManager.requireGraphFingerprint(),
                    properties.routing().safetyRadiusMeters(),
                    blocked.matchedCameraCount(),
                    blocked.unmatchedCameraIds());
        } catch (IOException | IllegalArgumentException exception) {
            throw new SnapshotBuildException("camera snapshot build failed", exception);
        }
    }
}
