package cn.camera.safe.validation;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.PlannedRoute;
import cn.camera.safe.routing.RouteLeg;
import cn.camera.safe.routing.RoutePlanningMode;
import cn.camera.safe.routing.RouteTracePoint;
import cn.camera.safe.routing.RoadClassificationIndex;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;

class RouteSafetyValidatorTest {
    private final RouteSafetyValidator validator = new RouteSafetyValidator();

    @Test
    void independentlyDetectsRouteGeometryInsideCameraRadius() {
        RoutingSnapshot snapshot = snapshot(new Wgs84Coordinate(116.4, 39.9));
        List<Wgs84Coordinate> route = List.of(
                new Wgs84Coordinate(116.399, 39.9),
                new Wgs84Coordinate(116.401, 39.9));

        SafetyValidationResult result = validator.validate(route, snapshot);

        assertThat(result.compliant()).isFalse();
        assertThat(result.conflictCount()).isEqualTo(1);
        assertThat(result.conflicts().getFirst().cameraId()).isEqualTo("camera");
        assertThat(result.conflicts().getFirst().distanceMeters()).isLessThan(0.01);
    }

    @Test
    void acceptsRouteOutsideRadiusAndDetectsRestrictedEndpoint() {
        RoutingSnapshot snapshot = snapshot(new Wgs84Coordinate(116.4, 39.9));
        List<Wgs84Coordinate> route = List.of(
                new Wgs84Coordinate(116.399, 39.901),
                new Wgs84Coordinate(116.401, 39.901));

        assertThat(validator.validate(route, snapshot).compliant()).isTrue();
        assertThat(validator.isRestricted(new Wgs84Coordinate(116.4, 39.9001), snapshot)).isTrue();
        assertThat(validator.isRestricted(new Wgs84Coordinate(116.4, 39.901), snapshot)).isFalse();
    }

    @Test
    void ignoresCameraConflictsOnClassifiedHighwayMainlineTraceOnly() {
        Wgs84Coordinate from = new Wgs84Coordinate(116.399, 39.9);
        Wgs84Coordinate to = new Wgs84Coordinate(116.401, 39.9);
        RoutingSnapshot snapshot = snapshot(
                new Wgs84Coordinate(116.4, 39.9), classification(1));
        RouteLeg leg = new RouteLeg(100, 1_000, List.of(from, to));
        PlannedRoute route = new PlannedRoute(
                RoutePlanningMode.INTERNAL_SAFE,
                "boundary",
                null, null, null,
                leg, null, null,
                100, 1_000,
                leg.geometry(),
                List.of(
                        tracePoint(0, from, 1),
                        tracePoint(1, to, 1)),
                0, 0, 0);

        assertThat(validator.validate(route, snapshot).conflictCount()).isZero();
        Wgs84Coordinate camera = new Wgs84Coordinate(116.4, 39.9);
        assertThat(validator.isRestricted(camera, snapshot, 1)).isFalse();
        assertThat(validator.isRestricted(camera, snapshot, 2)).isTrue();
    }

    private static RoutingSnapshot snapshot(Wgs84Coordinate coordinate) {
        return snapshot(coordinate, classification(-1));
    }

    private static RoutingSnapshot snapshot(
            Wgs84Coordinate coordinate,
            RoadClassificationIndex classification) {
        CameraPoint camera = new CameraPoint(
                "camera", "district",
                new Gcj02Coordinate(coordinate.lng(), coordinate.lat()), coordinate,
                "address", "type", null);
        CameraSnapshot cameras = new CameraSnapshot(
                "camera-v1", "hash", Instant.EPOCH, List.of(camera));
        return new RoutingSnapshot(
                cameras,
                new CameraSpatialIndex(cameras.cameras()),
                BlockedEdgeSnapshot.empty(),
                "graph-v1",
                30,
                1,
                List.of(),
                1, 0, "boundary", 0, 0,
                classification);
    }

    private static RoadClassificationIndex classification(int highwayBaseEdgeId) {
        BitSet highway = new BitSet();
        if (highwayBaseEdgeId >= 0) {
            highway.set(highwayBaseEdgeId);
        }
        return new RoadClassificationIndex(
                highway, new BitSet(), new BitSet(), "test");
    }

    private static RouteTracePoint tracePoint(
            int geometryIndex,
            Wgs84Coordinate coordinate,
            int baseEdgeId) {
        return new RouteTracePoint(
                geometryIndex,
                coordinate,
                "road",
                RoadClass.MOTORWAY,
                false,
                RoadEnvironment.ROAD,
                baseEdgeId * 2);
    }
}
