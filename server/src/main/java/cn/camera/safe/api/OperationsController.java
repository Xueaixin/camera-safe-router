package cn.camera.safe.api;

import cn.camera.safe.api.model.HealthResponse;
import cn.camera.safe.api.model.ReadinessResponse;
import cn.camera.safe.api.model.RefreshAccepted;
import cn.camera.safe.application.CameraRefreshService;
import cn.camera.safe.api.model.CameraUpdateResult;
import cn.camera.safe.camera.update.CameraUpdateService;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class OperationsController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperationsController.class);

    private final GraphHopperManager graphManager;
    private final RoutingSnapshotManager snapshotManager;
    private final CameraRefreshService refreshService;
    private final CameraUpdateService updateService;
    private final AdminAccessGuard adminAccessGuard;
    private final AppProperties properties;

    public OperationsController(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            CameraRefreshService refreshService,
            CameraUpdateService updateService,
            AdminAccessGuard adminAccessGuard,
            AppProperties properties) {
        this.graphManager = graphManager;
        this.snapshotManager = snapshotManager;
        this.refreshService = refreshService;
        this.updateService = updateService;
        this.adminAccessGuard = adminAccessGuard;
        this.properties = properties;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }

    @GetMapping("/readiness")
    public ResponseEntity<ReadinessResponse> readiness() {
        boolean graphLoaded = graphManager.isReady();
        RoutingSnapshot snapshot = snapshotManager.current().orElse(null);
        boolean cameraLoaded = snapshot != null;
        boolean blockedLoaded = snapshot != null
                && snapshot.blockedEdges().cameraSnapshotVersion()
                .equals(snapshot.cameraSnapshot().version());
        boolean ready = graphLoaded && cameraLoaded && blockedLoaded;
        String reason = ready ? null : notReadyReason(graphLoaded, cameraLoaded, blockedLoaded);
        ReadinessResponse response = new ReadinessResponse(
                ready ? "READY" : "NOT_READY",
                graphLoaded,
                cameraLoaded,
                blockedLoaded,
                reason);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    @PostMapping("/admin/camera-snapshots/refresh")
    public ResponseEntity<RefreshAccepted> refresh(HttpServletRequest request) {
        adminAccessGuard.requireLocal(request);
        String jobId = refreshService.requestRefresh();
        return ResponseEntity.accepted().body(new RefreshAccepted(jobId, "ACCEPTED"));
    }

    @PostMapping("/admin/camera-data/update")
    public CameraUpdateResult updateCameraData(HttpServletRequest request) {
        adminAccessGuard.requireLocal(request);
        LOGGER.info("已接受手动摄像头数据更新请求 远程地址={}",
                request.getRemoteAddr());
        return updateService.updateNow();
    }

    private String notReadyReason(boolean graphLoaded, boolean cameraLoaded, boolean blockedLoaded) {
        if (!graphLoaded) {
            String failure = graphManager.failureReason();
            return failure == null ? "路网正在加载" : "路网加载失败: " + failure;
        }
        if (!properties.cameras().sourceCoordinateVerified()) {
            return "摄像头源坐标系尚未人工确认";
        }
        if (!cameraLoaded) {
            String failure = snapshotManager.lastFailure();
            return failure == null ? "摄像头快照正在加载" : "摄像头快照加载失败: " + failure;
        }
        if (!blockedLoaded) {
            return "禁行边快照版本不一致";
        }
        return "服务尚未就绪";
    }
}
