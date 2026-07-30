package cn.camera.safe.validation;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.RoutingSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

    private static RoutingSnapshot snapshot(Wgs84Coordinate coordinate) {
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
                List.of());
    }
}
