package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.CameraPage;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static cn.camera.safe.application.RoutePlanningServiceTest.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CameraQueryServiceTest {
    @Test
    void returnsCoordinatesInTheExplicitRequestedSystemWithoutAnArbitrarySpanLimit() {
        RoutingSnapshotManager manager = mock(RoutingSnapshotManager.class);
        when(manager.current()).thenReturn(Optional.of(snapshot()));
        AppProperties properties = properties();
        CameraQueryService service = new CameraQueryService(
                manager, new CoordinateConverter(), properties);

        CameraPage gcj = service.query(
                116.39, 39.89, 116.41, 39.91, CoordinateSystem.GCJ02);
        assertThat(gcj.coordinateSystem()).isEqualTo(CoordinateSystem.GCJ02);
        assertThat(gcj.items()).singleElement().satisfies(camera -> {
            assertThat(camera.lng()).isEqualTo(116.4);
            assertThat(camera.lat()).isEqualTo(39.9);
        });

        CameraPage wide = service.query(
                115.840917, 39.736757, 116.973883, 40.071235, CoordinateSystem.GCJ02);
        assertThat(wide.coordinateSystem()).isEqualTo(CoordinateSystem.GCJ02);
        assertThat(wide.items()).hasSize(1);
    }

    @Test
    void rejectsOnlyWhenTheActualResultCountExceedsTheConfiguredLimit() {
        RoutingSnapshotManager manager = mock(RoutingSnapshotManager.class);
        when(manager.current()).thenReturn(Optional.of(snapshot(101)));
        CameraQueryService service = new CameraQueryService(
                manager, new CoordinateConverter(), properties());

        assertThatThrownBy(() -> service.query(115, 39, 117, 41, CoordinateSystem.WGS84))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> {
                            assertThat(exception.code())
                                    .isEqualTo(ErrorCode.CAMERA_QUERY_RESULT_LIMIT_EXCEEDED);
                            assertThat(exception.status().value()).isEqualTo(422);
                        });
    }

    private static RoutingSnapshot snapshot() {
        return snapshot(1);
    }

    private static RoutingSnapshot snapshot(int cameraCount) {
        CoordinateConverter converter = new CoordinateConverter();
        Gcj02Coordinate gcj = new Gcj02Coordinate(116.4, 39.9);
        List<CameraPoint> points = IntStream.range(0, cameraCount)
                .mapToObj(index -> new CameraPoint(
                        "camera-" + index, "district", gcj, converter.toWgs84(gcj),
                        "address", "type", null))
                .toList();
        CameraSnapshot cameras = new CameraSnapshot(
                "camera-v1", "hash", Instant.EPOCH, points);
        return new RoutingSnapshot(
                cameras,
                new CameraSpatialIndex(cameras.cameras()),
                new BlockedEdgeSnapshot(new java.util.BitSet(), new java.util.BitSet(),
                        "camera-v1", "blocked-v1"),
                "graph-v1", 30, 1, List.of());
    }
}
