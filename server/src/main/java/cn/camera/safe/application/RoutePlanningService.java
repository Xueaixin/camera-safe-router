package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.BoundaryCrossing;
import cn.camera.safe.api.model.BoundaryDirection;
import cn.camera.safe.api.model.BoundaryRole;
import cn.camera.safe.api.model.InputCoordinate;
import cn.camera.safe.api.model.OutputCoordinate;
import cn.camera.safe.api.model.RouteRequest;
import cn.camera.safe.api.model.RouteResponse;
import cn.camera.safe.api.model.RouteSegment;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.ExecutorConfiguration;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.PlannedRoute;
import cn.camera.safe.routing.RouteLeg;
import cn.camera.safe.routing.RoutePlanner;
import cn.camera.safe.routing.RoutingEngineException;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import cn.camera.safe.routing.SixthRingPortal;
import cn.camera.safe.routing.SixthRingRouteException;
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
    private final RoutePlanner routePlanner;
    private final RouteSafetyValidator safetyValidator;
    private final ExecutorService executor;
    private final AppProperties properties;

    public RoutePlanningService(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            CoordinateConverter coordinateConverter,
            RoutePlanner routePlanner,
            RouteSafetyValidator safetyValidator,
            @Qualifier(ExecutorConfiguration.ROUTE_EXECUTOR) ExecutorService executor,
            AppProperties properties) {
        this.graphManager = graphManager;
        this.snapshotManager = snapshotManager;
        this.coordinateConverter = coordinateConverter;
        this.routePlanner = routePlanner;
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

        PlannedRoute plannedRoute = calculate(start, end, snapshot);
        RouteLeg safeLeg = plannedRoute.safeSegment();
        if (safeLeg != null) {
            SafetyValidationResult safety = safetyValidator.validate(safeLeg.geometry(), snapshot);
            if (!safety.compliant()) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        ErrorCode.ROUTE_CONFLICT_DETECTED,
                        "路线独立安全校验检测到摄像头冲突",
                        Map.of("cameraConflictCount", safety.conflictCount()));
            }
        }

        String routeId = UUID.randomUUID().toString();
        List<OutputCoordinate> geometry = outputGeometry(plannedRoute.geometry());
        LOGGER.info("路线规划完成 路线ID={} 模式={} 边界版本={} 通行口={} "
                        + "摄像头版本={} 禁行边版本={} 距离米={} "
                        + "搜索检查边数={} 虚拟边检查数={} 禁行拒绝数={}",
                routeId,
                plannedRoute.planningMode(),
                plannedRoute.boundaryVersion(),
                plannedRoute.boundaryCrossing() == null
                        ? null : plannedRoute.boundaryCrossing().id(),
                snapshot.cameraSnapshot().version(),
                snapshot.blockedEdges().blockedEdgeVersion(),
                Math.round(plannedRoute.distanceMeters()),
                plannedRoute.searchEdgeChecks(),
                plannedRoute.virtualEdgeChecks(),
                plannedRoute.blockedRejections());
        return new RouteResponse(
                routeId,
                cn.camera.safe.coordinate.CoordinateSystem.GCJ02,
                cn.camera.safe.api.model.RoutePlanningMode.valueOf(
                        plannedRoute.planningMode().name()),
                plannedRoute.boundaryVersion(),
                plannedRoute.boundaryDirection() == null
                        ? null : BoundaryDirection.valueOf(plannedRoute.boundaryDirection().name()),
                boundaryCrossing(plannedRoute.boundaryCrossing()),
                segment(plannedRoute.safeSegment()),
                segment(plannedRoute.referenceSegment()),
                plannedRoute.distanceMeters(),
                Math.max(0, plannedRoute.durationMillis() / 1_000),
                0,
                snapshot.cameraSnapshot().version(),
                snapshot.blockedEdges().blockedEdgeVersion(),
                geometry,
                List.of());
    }

    private PlannedRoute calculate(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        Future<PlannedRoute> future;
        try {
            future = executor.submit(() -> routePlanner.plan(start, end, snapshot));
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
                    ErrorCode.ROUTE_SEARCH_TIMEOUT, "路线计算超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTE_SEARCH_TIMEOUT, "路线计算被中断");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SixthRingRouteException routeException) {
                throw map(routeException);
            }
            if (cause instanceof RoutingEngineException engineException) {
                throw map(engineException);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("route calculation failed", cause);
        }
    }

    private BusinessException map(SixthRingRouteException exception) {
        return switch (exception.reason()) {
            case TOPOLOGY_NOT_READY -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.SIXTH_RING_TOPOLOGY_NOT_READY, "六环路由拓扑尚未就绪");
            case BOUNDARY_AMBIGUOUS -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.SIXTH_RING_BOUNDARY_AMBIGUOUS, exception.getMessage());
            case POINT_NOT_FOUND -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.OUTSIDE_ROUTING_BOUNDS, "起点或终点无法吸附到当前路网");
            case NO_ROUTE -> new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.NO_COMPLIANT_ROUTE, "未找到能够避开当前限制点位的路线");
            case SEARCH_TIMEOUT -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTE_SEARCH_TIMEOUT, "六环通行口搜索超时，请稍后重试");
            case RESOURCE_LIMIT -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTE_SEARCH_RESOURCE_LIMIT, "六环通行口搜索达到资源上限，请稍后重试");
            case REFERENCE_ROUTE_FAILED -> new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.REFERENCE_ROUTE_FAILED, "环内路线可达，但完整参考路线生成失败");
        };
    }

    private BusinessException map(RoutingEngineException exception) {
        if (exception.reason() == RoutingEngineException.Reason.POINT_NOT_FOUND) {
            return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.OUTSIDE_ROUTING_BOUNDS, "起点或终点无法吸附到当前路网");
        }
        if (exception.reason() == RoutingEngineException.Reason.RESOURCE_LIMIT) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTE_SEARCH_RESOURCE_LIMIT, "路线搜索达到资源上限，请稍后重试");
        }
        return new BusinessException(HttpStatus.CONFLICT,
                ErrorCode.NO_COMPLIANT_ROUTE, "未找到能够避开当前限制点位的路线");
    }

    private BoundaryCrossing boundaryCrossing(SixthRingPortal portal) {
        if (portal == null) {
            return null;
        }
        var gcj02 = coordinateConverter.toGcj02(portal.crossing());
        return new BoundaryCrossing(
                portal.id(),
                portal.roadName().isBlank() ? null : portal.roadName(),
                BoundaryDirection.valueOf(portal.direction().name()),
                BoundaryRole.valueOf(portal.boundaryRole().name()),
                new OutputCoordinate(portal.crossing().lng(), portal.crossing().lat()),
                new OutputCoordinate(gcj02.lng(), gcj02.lat()));
    }

    private RouteSegment segment(RouteLeg leg) {
        if (leg == null) {
            return null;
        }
        return new RouteSegment(
                leg.distanceMeters(),
                Math.max(0, leg.durationMillis() / 1_000),
                outputGeometry(leg.geometry()));
    }

    private List<OutputCoordinate> outputGeometry(List<Wgs84Coordinate> geometry) {
        return geometry.stream()
                .map(coordinateConverter::toGcj02)
                .map(point -> new OutputCoordinate(point.lng(), point.lat()))
                .toList();
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
