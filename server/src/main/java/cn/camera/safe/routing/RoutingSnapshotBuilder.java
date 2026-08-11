package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.camera.CameraLoadResult;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.SixthRingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Component
public final class RoutingSnapshotBuilder {
    private final AppProperties properties;
    private final GraphHopperManager graphManager;
    private final CameraJsonLoader cameraLoader;
    private final BlockedEdgeGenerator blockedEdgeGenerator;
    private final SixthRingRoutingManager sixthRingRoutingManager;
    private final SixthRingProperties sixthRingProperties;

    @Autowired
    public RoutingSnapshotBuilder(
            AppProperties properties,
            GraphHopperManager graphManager,
            CameraJsonLoader cameraLoader,
            BlockedEdgeGenerator blockedEdgeGenerator,
            SixthRingRoutingManager sixthRingRoutingManager,
            SixthRingProperties sixthRingProperties) {
        this.properties = properties;
        this.graphManager = graphManager;
        this.cameraLoader = cameraLoader;
        this.blockedEdgeGenerator = blockedEdgeGenerator;
        this.sixthRingRoutingManager = sixthRingRoutingManager;
        this.sixthRingProperties = sixthRingProperties;
    }

    RoutingSnapshotBuilder(
            AppProperties properties,
            GraphHopperManager graphManager,
            CameraJsonLoader cameraLoader,
            BlockedEdgeGenerator blockedEdgeGenerator) {
        this(properties, graphManager, cameraLoader, blockedEdgeGenerator, null, null);
    }

    public RoutingSnapshot build() {
        return build(Path.of(properties.cameras().jsonPath()));
    }

    public RoutingSnapshot build(Path cameraPath) {
        if (!properties.cameras().sourceCoordinateVerified()) {
            throw new SnapshotBuildException(
                    "摄像头源坐标系尚未完成人工确认");
        }
        if (!graphManager.isReady()) {
            throw new SnapshotBuildException("路网尚未就绪");
        }
        try {
            CameraLoadResult loaded = cameraLoader.load(cameraPath);
            if (!loaded.isValid()) {
                String firstIssue = loaded.issues().isEmpty()
                        ? "no records"
                        : loaded.issues().getFirst().reason();
                throw new SnapshotBuildException("摄像头 JSON 校验失败: 有效数="
                        + loaded.cameras().size() + " 保留数=" + loaded.retainedRecordCount()
                        + " 源记录数=" + loaded.sourceRecordCount()
                        + " 六环外排除数=" + loaded.outsideSixRingRecordCount()
                        + " 无法识别IsSixRingOut数=" + loaded.unrecognizedSixRingOutRecordCount()
                        + " 问题数=" + loaded.issues().size() + " 首个问题=" + firstIssue);
            }
            CameraSnapshot cameraSnapshot = CameraSnapshot.from(loaded);
            SixthRingBoundary boundary = sixthRingRoutingManager == null
                    ? null : sixthRingRoutingManager.requireContext().boundary();
            RoadClassificationIndex roadClassification = sixthRingRoutingManager == null
                    ? RoadClassificationIndex.empty(graphManager.requireGraphFingerprint())
                    : sixthRingRoutingManager.requireContext().roadClassification();
            double outsideMarginMeters = sixthRingProperties == null
                    ? 0 : sixthRingProperties.cameraOutsideMarginMeters();
            List<cn.camera.safe.camera.CameraPoint> restrictedCameras = boundary == null
                    ? cameraSnapshot.cameras()
                    : cameraSnapshot.cameras().stream()
                            .filter(camera -> boundary.controls(camera.wgs84(), outsideMarginMeters))
                            .toList();
            String boundaryVersion = boundary == null ? "legacy-unscoped" : boundary.version();
            CameraSpatialIndex cameraIndex = new CameraSpatialIndex(restrictedCameras);
            BlockedEdgeBuildResult blocked = blockedEdgeGenerator.generate(
                    cameraSnapshot,
                    restrictedCameras,
                    graphManager.requireRoadEdgeIndex(),
                    properties.routing().safetyRadiusMeters(),
                    boundaryVersion,
                    outsideMarginMeters,
                    roadClassification);
            return new RoutingSnapshot(
                    cameraSnapshot,
                    cameraIndex,
                    blocked.snapshot(),
                    graphManager.requireGraphFingerprint(),
                    properties.routing().safetyRadiusMeters(),
                    blocked.matchedCameraCount(),
                    blocked.unmatchedCameraIds(),
                    restrictedCameras.size(),
                    cameraSnapshot.cameras().size() - restrictedCameras.size(),
                    boundaryVersion,
                    outsideMarginMeters,
                    blocked.highwayExemptCameraCount(),
                    roadClassification);
        } catch (IOException | IllegalArgumentException exception) {
            throw new SnapshotBuildException("摄像头快照构建失败", exception);
        }
    }
}
