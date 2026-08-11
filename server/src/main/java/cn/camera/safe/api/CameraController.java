package cn.camera.safe.api;

import cn.camera.safe.api.model.CameraPage;
import cn.camera.safe.api.model.CameraSnapshotStatus;
import cn.camera.safe.application.CameraQueryService;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class CameraController {
    private final CameraQueryService cameraQueryService;
    private final RoutingSnapshotManager snapshotManager;

    public CameraController(
            CameraQueryService cameraQueryService,
            RoutingSnapshotManager snapshotManager) {
        this.cameraQueryService = cameraQueryService;
        this.snapshotManager = snapshotManager;
    }

    @GetMapping("/cameras")
    public CameraPage listCameras(
            @RequestParam double minLng,
            @RequestParam double minLat,
            @RequestParam double maxLng,
            @RequestParam double maxLat,
            @RequestParam CoordinateSystem coordinateSystem) {
        return cameraQueryService.query(
                minLng, minLat, maxLng, maxLat, coordinateSystem);
    }

    @GetMapping("/camera-snapshots/current")
    public CameraSnapshotStatus currentSnapshot() {
        RoutingSnapshot snapshot = snapshotManager.current().orElseThrow(() ->
                new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.CAMERA_SNAPSHOT_NOT_READY, "摄像头快照尚未就绪"));
        return new CameraSnapshotStatus(
                "READY",
                snapshot.cameraSnapshot().version(),
                snapshot.blockedEdges().blockedEdgeVersion(),
                snapshot.restrictedCameraCount(),
                snapshot.cameraSnapshot().cameras().size(),
                snapshot.outsideControlAreaCameraCount(),
                snapshot.cameraOutsideMarginMeters(),
                snapshot.controlBoundaryVersion(),
                snapshot.safetyRadiusMeters(),
                snapshot.cameraSnapshot().loadedAt());
    }
}
