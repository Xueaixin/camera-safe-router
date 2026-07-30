package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingSnapshotManagerTest {
    @Test
    void retainsLastPublishedSnapshotWhenTheNextBuildFails() throws Exception {
        RoutingSnapshotBuilder builder = mock(RoutingSnapshotBuilder.class);
        RoutingSnapshotStore store = mock(RoutingSnapshotStore.class);
        RoutingSnapshot initial = snapshot("camera-v1", "blocked-v1");
        when(builder.build()).thenReturn(initial)
                .thenThrow(new SnapshotBuildException("invalid replacement"));
        RoutingSnapshotManager manager = new RoutingSnapshotManager(builder, store);

        assertThat(manager.refreshNow()).isSameAs(initial);
        assertThatThrownBy(manager::refreshNow).isInstanceOf(SnapshotBuildException.class);

        assertThat(manager.current()).contains(initial);
        assertThat(manager.lastFailure()).contains("invalid replacement");
        verify(store).persist(initial);
    }

    private static RoutingSnapshot snapshot(String cameraVersion, String blockedVersion) {
        Wgs84Coordinate coordinate = new Wgs84Coordinate(116.4, 39.9);
        CameraPoint camera = new CameraPoint(
                "camera", "district", new Gcj02Coordinate(116.4, 39.9), coordinate,
                "address", "type", null);
        CameraSnapshot cameras = new CameraSnapshot(
                cameraVersion, "hash", Instant.EPOCH, List.of(camera));
        return new RoutingSnapshot(
                cameras,
                new CameraSpatialIndex(cameras.cameras()),
                new BlockedEdgeSnapshot(new java.util.BitSet(), new java.util.BitSet(),
                        cameraVersion, blockedVersion),
                "graph-v1",
                30,
                1,
                List.of());
    }
}
