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
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.NavigationHandoffPoint;
import cn.camera.safe.routing.PlannedRoute;
import cn.camera.safe.routing.RouteLeg;
import cn.camera.safe.routing.RoutePlanner;
import cn.camera.safe.routing.RoutePlanningMode;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import cn.camera.safe.routing.SixthRingPortal;
import cn.camera.safe.routing.SixthRingRouteException;
import cn.camera.safe.validation.RouteSafetyValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        RoutePlanner planner = mock(RoutePlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(plannedRoute(
                List.of(new Wgs84Coordinate(116.39, 39.90),
                        new Wgs84Coordinate(116.41, 39.91))));
        RoutePlanningService service = service(graphManager, snapshotManager, planner);

        RouteResponse response = service.plan(request());

        assertThat(response.cameraSnapshotVersion()).isEqualTo("camera-v1");
        assertThat(response.blockedEdgeVersion()).isEqualTo("blocked-v1");
        assertThat(response.cameraConflictCount()).isZero();
        assertThat(response.coordinateSystem()).isEqualTo(CoordinateSystem.GCJ02);
        assertThat(response.planningMode())
                .isEqualTo(cn.camera.safe.api.model.RoutePlanningMode.INTERNAL_SAFE);
        assertThat(response.boundaryVersion()).isEqualTo("boundary-v1");
        assertThat(response.safeSegment()).isNotNull();
        assertThat(response.referenceSegment()).isNull();
        verify(planner).plan(any(), any(), org.mockito.ArgumentMatchers.same(first));
        verify(snapshotManager).current();
    }

    @Test
    void rejectsRestrictedStartBeforeCallingTheRoutingEngine() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        RoutingSnapshot snapshot = snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.39, 39.90));
        when(snapshotManager.current()).thenReturn(Optional.of(snapshot));
        RoutePlanner planner = mock(RoutePlanner.class);
        RoutePlanningService service = service(graphManager, snapshotManager, planner);

        assertThatThrownBy(() -> service.plan(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.START_IN_RESTRICTED_AREA));
        verify(planner, never()).plan(any(), any(), any());
    }

    @Test
    void mapsNoPathToNoCompliantRouteAndNeverReturnsAFallback() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        when(snapshotManager.current()).thenReturn(Optional.of(
                snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.5, 40.0))));
        RoutePlanner planner = mock(RoutePlanner.class);
        when(planner.plan(any(), any(), any())).thenThrow(
                new SixthRingRouteException(SixthRingRouteException.Reason.NO_ROUTE, "none"));
        RoutePlanningService service = service(graphManager, snapshotManager, planner);

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
        RoutePlanner planner = mock(RoutePlanner.class);
        when(planner.plan(any(), any(), any())).thenReturn(plannedRoute(
                List.of(new Wgs84Coordinate(116.399, 39.905),
                        new Wgs84Coordinate(116.401, 39.905))));
        RoutePlanningService service = service(graphManager, snapshotManager, planner);

        assertThatThrownBy(() -> service.plan(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.code()).isEqualTo(ErrorCode.ROUTE_CONFLICT_DETECTED));
    }

    @Test
    void externalOnlyRouteSkipsIndependentCameraValidation() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        when(snapshotManager.current()).thenReturn(Optional.of(
                snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.4, 39.905))));
        RoutePlanner planner = mock(RoutePlanner.class);
        Wgs84Coordinate start = new Wgs84Coordinate(116.39, 39.90);
        Wgs84Coordinate end = new Wgs84Coordinate(116.41, 39.91);
        when(planner.plan(any(), any(), any())).thenReturn(new PlannedRoute(
                RoutePlanningMode.EXTERNAL_ONLY,
                "boundary-v1",
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                List.of(start, end),
                0,
                0,
                0));

        RouteResponse response = service(graphManager, snapshotManager, planner).plan(request());

        assertThat(response.planningMode())
                .isEqualTo(cn.camera.safe.api.model.RoutePlanningMode.EXTERNAL_ONLY);
        assertThat(response.cameraConflictCount()).isZero();
        assertThat(response.geometry()).hasSize(2);
    }

    private RoutePlanningService service(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            RoutePlanner planner) {
        return new RoutePlanningService(
                graphManager,
                snapshotManager,
                new CoordinateConverter(),
                planner,
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
                        "pbf", "cache", "candidate-cache", RoutingProfileMode.CURRENT,
                        30, 1, 2,
                        Duration.ofSeconds(2), 10_000),
                new AppProperties.Cameras(
                        "cameras", "snapshots", true, 100,
                        updateProperties()),
                new AppProperties.Admin(true));
    }

    @Test
    void mapsCrossBoundarySegmentsAndValidatesTheFullGeometry() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        RoutingSnapshot snapshot = snapshot(
                "camera-v1", "blocked-v1", new Wgs84Coordinate(116.8, 40.4));
        when(snapshotManager.current()).thenReturn(Optional.of(snapshot));
        RoutePlanner planner = mock(RoutePlanner.class);
        Wgs84Coordinate start = new Wgs84Coordinate(116.39, 39.90);
        Wgs84Coordinate crossing = new Wgs84Coordinate(116.40, 39.905);
        Wgs84Coordinate handoff = new Wgs84Coordinate(116.5, 40.0);
        Wgs84Coordinate end = new Wgs84Coordinate(116.41, 39.91);
        RouteLeg safe = new RouteLeg(900, 100_000, List.of(start, crossing, handoff));
        RouteLeg reference = new RouteLeg(600, 80_000, List.of(handoff, end));
        SixthRingPortal portal = new SixthRingPortal(
                "portal-1",
                10,
                20,
                SixthRingPortal.Direction.OUTBOUND,
                SixthRingPortal.BoundaryRole.OUTER_EXIT,
                SixthRingPortal.CandidateType.INTERIOR_EDGE,
                -1,
                0.5,
                crossing,
                "六环路");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannedRoute(
                RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND,
                "boundary-v1",
                SixthRingPortal.Direction.OUTBOUND,
                portal,
                new NavigationHandoffPoint(
                        handoff,
                        320,
                        "沙河路",
                        NavigationHandoffPoint.Segment.REFERENCE,
                        1),
                safe,
                reference,
                new cn.camera.safe.routing.ExternalHandoffPoint(
                        new Wgs84Coordinate(116.6, 40.1), 500, 200),
                1_500,
                180_000,
                List.of(start, crossing, new Wgs84Coordinate(116.5, 40.0), end),
                100,
                5,
                3));

        RouteResponse response = service(graphManager, snapshotManager, planner).plan(request());

        assertThat(response.planningMode())
                .isEqualTo(cn.camera.safe.api.model.RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND);
        assertThat(response.boundaryCrossing().portalId()).isEqualTo("portal-1");
        assertThat(response.boundaryCrossing().wgs84().lng()).isEqualTo(crossing.lng());
        assertThat(response.boundaryCrossing().gcj02().lng()).isNotEqualTo(crossing.lng());
        assertThat(response.navigationHandoff().roadName()).isEqualTo("沙河路");
        assertThat(response.safeSegment().geometry()).hasSize(3);
        assertThat(response.referenceSegment().geometry()).hasSize(2);
    }

    @Test
    void rejectsAConflictOnTheCrossBoundaryReferenceSegment() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        RoutingSnapshot snapshot = snapshot(
                "camera-v1", "blocked-v1", new Wgs84Coordinate(116.5, 40.0));
        when(snapshotManager.current()).thenReturn(Optional.of(snapshot));
        RoutePlanner planner = mock(RoutePlanner.class);
        Wgs84Coordinate start = new Wgs84Coordinate(116.39, 39.90);
        Wgs84Coordinate crossing = new Wgs84Coordinate(116.40, 39.905);
        Wgs84Coordinate handoff = new Wgs84Coordinate(116.5, 40.0);
        Wgs84Coordinate end = new Wgs84Coordinate(116.41, 39.91);
        RouteLeg safe = new RouteLeg(900, 100_000, List.of(start, crossing, handoff));
        RouteLeg reference = new RouteLeg(600, 80_000, List.of(handoff, end));
        SixthRingPortal portal = new SixthRingPortal(
                "portal-1",
                10,
                20,
                SixthRingPortal.Direction.OUTBOUND,
                SixthRingPortal.BoundaryRole.OUTER_EXIT,
                SixthRingPortal.CandidateType.INTERIOR_EDGE,
                -1,
                0.5,
                crossing,
                "六环路");
        when(planner.plan(any(), any(), any())).thenReturn(new PlannedRoute(
                RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND,
                "boundary-v1",
                SixthRingPortal.Direction.OUTBOUND,
                portal,
                new NavigationHandoffPoint(
                        handoff,
                        320,
                        "沙河路",
                        NavigationHandoffPoint.Segment.REFERENCE,
                        1),
                safe,
                reference,
                new cn.camera.safe.routing.ExternalHandoffPoint(
                        new Wgs84Coordinate(116.6, 40.1), 500, 200),
                1_500,
                180_000,
                List.of(start, crossing, new Wgs84Coordinate(116.5, 40.0), end),
                100,
                5,
                3));

        assertThatThrownBy(() -> service(graphManager, snapshotManager, planner).plan(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ErrorCode.ROUTE_CONFLICT_DETECTED));
    }

    @Test
    void keepsDistinctSixthRingFailureCodes() {
        Map<SixthRingRouteException.Reason, ExpectedError> cases = Map.of(
                SixthRingRouteException.Reason.TOPOLOGY_NOT_READY,
                new ExpectedError(ErrorCode.SIXTH_RING_TOPOLOGY_NOT_READY, 503),
                SixthRingRouteException.Reason.BOUNDARY_AMBIGUOUS,
                new ExpectedError(ErrorCode.SIXTH_RING_BOUNDARY_AMBIGUOUS, 422),
                SixthRingRouteException.Reason.SEARCH_TIMEOUT,
                new ExpectedError(ErrorCode.ROUTE_SEARCH_TIMEOUT, 503),
                SixthRingRouteException.Reason.REFERENCE_ROUTE_FAILED,
                new ExpectedError(ErrorCode.REFERENCE_ROUTE_FAILED, 503));

        cases.forEach((reason, expected) -> {
            GraphHopperManager graphManager = readyGraphManager();
            RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
            when(snapshotManager.current()).thenReturn(Optional.of(
                    snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.5, 40.0))));
            RoutePlanner planner = mock(RoutePlanner.class);
            when(planner.plan(any(), any(), any())).thenThrow(
                    new SixthRingRouteException(reason, reason.name()));

            assertThatThrownBy(() -> service(graphManager, snapshotManager, planner).plan(request()))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.code()).isEqualTo(expected.code());
                        assertThat(exception.status().value()).isEqualTo(expected.status());
                    });
        });
    }

    private static PlannedRoute plannedRoute(List<Wgs84Coordinate> geometry) {
        RouteLeg leg = new RouteLeg(1_000, 120_000, geometry);
        return new PlannedRoute(
                RoutePlanningMode.INTERNAL_SAFE,
                "boundary-v1",
                null,
                null,
                null,
                leg,
                null,
                null,
                leg.distanceMeters(),
                leg.durationMillis(),
                leg.geometry(),
                42,
                2,
                1);
    }

    private record ExpectedError(ErrorCode code, int status) {
    }

    @Test
    void mapsSearchResourceLimitToServiceUnavailableInsteadOfNoCompliantRoute() {
        GraphHopperManager graphManager = readyGraphManager();
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        when(snapshotManager.current()).thenReturn(Optional.of(
                snapshot("camera-v1", "blocked-v1", new Wgs84Coordinate(116.5, 40.0))));
        RoutePlanner planner = mock(RoutePlanner.class);
        when(planner.plan(any(), any(), any())).thenThrow(
                new SixthRingRouteException(
                        SixthRingRouteException.Reason.RESOURCE_LIMIT, "limit"));
        RoutePlanningService service = service(graphManager, snapshotManager, planner);

        assertThatThrownBy(() -> service.plan(request()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.ROUTE_SEARCH_RESOURCE_LIMIT);
                    assertThat(exception.status().value()).isEqualTo(503);
                });
    }

    public static AppProperties.Update updateProperties() {
        return new AppProperties.Update(
                false,
                "https://example.test/cameras",
                "0 15 3 * * *",
                "Asia/Shanghai",
                "downloads",
                "failed",
                "backups",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                10 * 1024 * 1024,
                1,
                0.2,
                0.9,
                0.3,
                30,
                20,
                20);
    }
}
