package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedEdgeGeneratorTest {
    @Test
    void blocksBothDirectionsOfEveryLongEdgeGeometryIntersectingTheSafetyCircle() {
        BaseGraph graph = new BaseGraph.Builder(4).create();
        graph.getNodeAccess().setNode(0, 39.9, 116.0);
        graph.getNodeAccess().setNode(1, 39.9, 116.01);
        graph.getNodeAccess().setNode(2, 39.899, 116.005);
        graph.getNodeAccess().setNode(3, 39.901, 116.005);
        graph.getNodeAccess().setNode(4, 39.91, 116.0);
        graph.getNodeAccess().setNode(5, 39.91, 116.01);
        EdgeIteratorState eastWest = graph.edge(0, 1).setDistance(850);
        EdgeIteratorState northSouth = graph.edge(2, 3).setDistance(222);
        EdgeIteratorState farAway = graph.edge(4, 5).setDistance(850);

        RoadEdgeIndex roadIndex = RoadEdgeIndex.build(graph, edge -> true, "graph-test");
        CameraSnapshot cameras = snapshot(new Wgs84Coordinate(116.005, 39.9));
        BlockedEdgeBuildResult result = new BlockedEdgeGenerator().generate(cameras, roadIndex, 30);

        assertThat(result.matchedCameraCount()).isEqualTo(1);
        assertThat(result.unmatchedCameraIds()).isEmpty();
        assertThat(result.snapshot().isBlockedEdgeKey(eastWest.getEdgeKey())).isTrue();
        assertThat(result.snapshot().isBlockedEdgeKey(GHUtility.reverseEdgeKey(eastWest.getEdgeKey()))).isTrue();
        assertThat(result.snapshot().isBlockedEdgeKey(northSouth.getEdgeKey())).isTrue();
        assertThat(result.snapshot().isBlockedEdgeKey(GHUtility.reverseEdgeKey(northSouth.getEdgeKey()))).isTrue();
        assertThat(result.snapshot().isBlockedEdgeKey(farAway.getEdgeKey())).isFalse();
        assertThat(result.snapshot().blockedEdgeCount()).isEqualTo(2);
    }

    private static CameraSnapshot snapshot(Wgs84Coordinate coordinate) {
        CameraPoint camera = new CameraPoint(
                "camera", "district",
                new Gcj02Coordinate(coordinate.lng(), coordinate.lat()), coordinate,
                "address", "type", null);
        return new CameraSnapshot("camera-v1", "hash", Instant.EPOCH, List.of(camera));
    }
}
