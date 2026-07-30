package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.CoordinateSource;
import cn.camera.safe.api.model.InputCoordinate;
import cn.camera.safe.api.model.RouteRequest;
import cn.camera.safe.api.model.RouteResponse;
import cn.camera.safe.api.model.VehicleType;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.EngineRoute;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingEngine;
import cn.camera.safe.routing.RoutingEngineException;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import cn.camera.safe.validation.RouteSafetyValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RoutePlanningServiceTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void usesOneCapturedSnapshotVersionForRoutingValidationAndResponse() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        RoutingSnapshot first = snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.5, 40.0));
        RoutingSnapshot replacement = snapshot("camera-v2", "blocked-v2", new Wgs84Coordinate(116.6, 40.1));
        when(snapshotManager.current()).thenReturn(Optional.of(first), Optional.of(replacement));
        RoutingEngine engine = mock(RoutingEngine.class);
        when(engine.route(any(), any(), any())).thenReturn(new EngineRoute(
                1_000, 120_000,
                List.of(new Wgs84Coordinate(116.39, 39.90), new Wgs84Coordinate(116.41, 39.91)),
                42, 2, 1));
        RoutePlanningService service = service(graphManager, snapshotManager, engine);

        RouteResponse response = service.plan(request());

        assertThat(response.cameraSnapshotVersion()).isEqualTo("camera-v1");
        assertThat(response.blockedEdgeVersion()).isEqualTo("blocked-v1");
        assertThat(response.cameraConflictCount()).isZero();
        assertThat(response.coordinateSystem()).isEqualTo(CoordinateSystem.GCJ02);
        verify(engine).route(any(), any(), org.mockito.ArgumentMatchers.same(first));
        verify(snapshotManager).current();
    }

    @Test
    void rejectsRestrictedStartBeforeCallingTheRoutingEngine() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        RoutingSnapshot snapshot = snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.39, 39.90));
        when(snapshotManager.current()).thenReturn(Optional.of(snapshot));
        RoutingEngine engine = mock(RoutingEngine.class);
        RoutePlanningService service = service(graphManager, snapshotManager, engine);

        assertThatThrownBy(() -> service.plan(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.START_IN_RESTRICTED_AREA));
        verify(engine, never()).route(any(), any(), any());
    }

    @Test
    void mapsNoPathToNoCompliantRouteAndNeverReturnsAFallback() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        when(snapshotManager.current()).thenReturn(Optional.of(
                snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.5, 40.0))));
        RoutingEngine engine = mock(RoutingEngine.class);
        when(engine.route(any(), any(), any())).thenThrow(
                new RoutingEngineException(RoutingEngineException.Reason.NO_ROUTE, "none"));
        RoutePlanningService service = service(graphManager, snapshotManager, engine);

        assertThatThrownBy(() -> service.plan(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.NO_COMPLIANT_ROUTE);
                    assertThat(exception.status().value()).isEqualTo(409);
                });
    }

    @Test
    void rejectsEngineGeometryWhenIndependentCameraIndexFindsAConflict() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        when(snapshotManager.current()).thenReturn(Optional.of(
                snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.4, 39.905))));
        RoutingEngine engine = mock(RoutingEngine.class);
        when(engine.route(any(), any(), any())).thenReturn(new EngineRoute(
                1_000, 60_000,
                List.of(new Wgs84Coordinate(116.399, 39.905), new Wgs84Coordinate(116.401, 39.905)),
                10, 2, 0));
        RoutePlanningService service = service(graphManager, snapshotManager, engine);

        assertThatThrownBy(() -> service.plan(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.ROUTE_CONFLICT_DETECTED));
    }

    private RoutePlanningService service(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            RoutingEngine engine) {
        return new RoutePlanningService(
                graphManager,
                snapshotManager,
                new CoordinateConverter(),
                engine,
                new RouteSafetyValidator(),
                executor,
                properties());
    }

    private static GraphHopperManager readyGraphManager() {
        GraphHopperManager manager = mock(GraphHopperManager.class);
        when(manager.isReady()).thenReturn(true);
        when(manager.contains(any())).thenReturn(true);
        return manager;
    }

    private static RouteRequest request() {
        return new RouteRequest(
                new InputCoordinate(116.39, 39.90, CoordinateSystem.WGS84,
                        CoordinateSource.CURRENT_LOCATION, 10.0, Instant.EPOCH),
                new InputCoordinate(116.41, 39.91, CoordinateSystem.WGS84,
                        CoordinateSource.MAP_PICK, null, null),
                VehicleType.CAR);
    }

    private static RoutingSnapshot snapshot(
            String cameraVersion,
            String blockedVersion,
            Wgs84Coordinate cameraCoordinate) {
        CameraPoint camera = new CameraPoint(
                "camera", "district",
                new Gcj02Coordinate(cameraCoordinate.lng(), cameraCoordinate.lat()),
                cameraCoordinate, "address", "type", null);
        CameraSnapshot cameras = new CameraSnapshot(
                cameraVersion, "hash", Instant.EPOCH, List.of(camera));
        BlockedEdgeSnapshot blocked = new BlockedEdgeSnapshot(
                new java.util.BitSet(), new java.util.BitSet(), cameraVersion, blockedVersion);
        return new RoutingSnapshot(
                cameras, new CameraSpatialIndex(cameras.cameras()), blocked,
                "graph-v1", 30, 1, List.of());
    }

    public static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Routing(
                        "pbf", "cache", 30, 1, 2,
                        Duration.ofSeconds(2), 10_000),
                new AppProperties.Cameras(
                        "cameras", "snapshots", true, 100, 1),
                new AppProperties.Admin(true));
    }
}
