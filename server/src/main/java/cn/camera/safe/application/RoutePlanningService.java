package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.InputCoordinate;
import cn.camera.safe.api.model.OutputCoordinate;
import cn.camera.safe.api.model.RouteRequest;
import cn.camera.safe.api.model.RouteResponse;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.ExecutorConfiguration;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.EngineRoute;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingEngine;
import cn.camera.safe.routing.RoutingEngineException;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import cn.camera.safe.validation.RouteSafetyValidator;
import cn.camera.safe.validation.SafetyValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public final class RoutePlanningService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoutePlanningService.class);

    private final GraphHopperManager graphManager;
    private final RoutingSnapshotManager snapshotManager;
    private final CoordinateConverter coordinateConverter;
    private final RoutingEngine routingEngine;
    private final RouteSafetyValidator safetyValidator;
    private final ExecutorService executor;
    private final AppProperties properties;

    public RoutePlanningService(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            CoordinateConverter coordinateConverter,
            RoutingEngine routingEngine,
            RouteSafetyValidator safetyValidator,
            @Qualifier(ExecutorConfiguration.ROUTE_EXECUTOR) ExecutorService executor,
            AppProperties properties) {
        this.graphManager = graphManager;
        this.snapshotManager = snapshotManager;
        this.coordinateConverter = coordinateConverter;
        this.routingEngine = routingEngine;
        this.safetyValidator = safetyValidator;
        this.executor = executor;
        this.properties = properties;
    }

    public RouteResponse plan(RouteRequest request) {
        if (!graphManager.isReady()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路网尚未就绪");
        }
        RoutingSnapshot snapshot = snapshotManager.current().orElseThrow(() ->
                new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.CAMERA_SNAPSHOT_NOT_READY, "摄像头快照尚未就绪"));

        Wgs84Coordinate start = normalize(request.start());
        Wgs84Coordinate end = normalize(request.end());
        if (!graphManager.contains(start) || !graphManager.contains(end)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.OUTSIDE_ROUTING_BOUNDS, "起点或终点不在当前路网支持范围");
        }
        if (safetyValidator.isRestricted(start, snapshot)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.START_IN_RESTRICTED_AREA, "起点位于摄像头避让范围内");
        }
        if (safetyValidator.isRestricted(end, snapshot)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.END_IN_RESTRICTED_AREA, "终点位于摄像头避让范围内");
        }

        EngineRoute engineRoute = calculate(start, end, snapshot);
        SafetyValidationResult safety = safetyValidator.validate(engineRoute.geometry(), snapshot);
        if (!safety.compliant()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.ROUTE_CONFLICT_DETECTED,
                    "路线独立安全校验检测到摄像头冲突",
                    Map.of("cameraConflictCount", safety.conflictCount()));
        }

        String routeId = UUID.randomUUID().toString();
        List<OutputCoordinate> geometry = engineRoute.geometry().stream()
                .map(coordinateConverter::toGcj02)
                .map(point -> new OutputCoordinate(point.lng(), point.lat()))
                .toList();
        LOGGER.info("路线规划完成 路线ID={} 摄像头版本={} 禁行边版本={} 距离米={} "
                        + "搜索检查边数={} 虚拟边检查数={} 禁行拒绝数={}",
                routeId,
                snapshot.cameraSnapshot().version(),
                snapshot.blockedEdges().blockedEdgeVersion(),
                Math.round(engineRoute.distanceMeters()),
                engineRoute.searchEdgeChecks(),
                engineRoute.virtualEdgeChecks(),
                engineRoute.blockedRejections());
        return new RouteResponse(
                routeId,
                cn.camera.safe.coordinate.CoordinateSystem.GCJ02,
                engineRoute.distanceMeters(),
                Math.max(0, engineRoute.durationMillis() / 1_000),
                0,
                snapshot.cameraSnapshot().version(),
                snapshot.blockedEdges().blockedEdgeVersion(),
                geometry,
                List.of());
    }

    private EngineRoute calculate(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        Future<EngineRoute> future;
        try {
            future = executor.submit(() -> routingEngine.route(start, end, snapshot));
        } catch (RejectedExecutionException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路线计算队列已满");
        }
        try {
            return future.get(properties.routing().requestTimeout().toMillis() + 250,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路线计算超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路线计算被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RoutingEngineException engineException) {
                if (engineException.reason() == RoutingEngineException.Reason.POINT_NOT_FOUND) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            ErrorCode.OUTSIDE_ROUTING_BOUNDS, "起点或终点无法吸附到当前路网");
                }
                throw new BusinessException(HttpStatus.CONFLICT,
                        ErrorCode.NO_COMPLIANT_ROUTE, "未找到能够避开当前限制点位的路线");
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("route calculation failed", cause);
        }
    }

    private Wgs84Coordinate normalize(InputCoordinate coordinate) {
        try {
            return switch (coordinate.coordinateSystem()) {
                case WGS84 -> new Wgs84Coordinate(coordinate.lng(), coordinate.lat());
                case GCJ02 -> coordinateConverter.toWgs84(
                        new Gcj02Coordinate(coordinate.lng(), coordinate.lat()));
            };
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_COORDINATE, "坐标无效");
        }
    }
}
