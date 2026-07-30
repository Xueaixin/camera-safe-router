package cn.camera.safe.routing;

import com.graphhopper.routing.AlgorithmOptions;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.RoutingAlgorithm;
import com.graphhopper.routing.RoutingAlgorithmFactorySimple;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.shapes.GHPoint3D;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static com.graphhopper.routing.util.TraversalMode.NODE_BASED;
import static com.graphhopper.util.Parameters.Algorithms.DIJKSTRA_BI;
import static org.assertj.core.api.Assertions.assertThat;

class BlockedEdgeWeightingTest {

    @Test
    void blocksStorageAndReverseDirectionsDuringSearch() {
        BaseGraph graph = graphWithNodes(4);
        EdgeIteratorState directFirst = graph.edge(0, 1).setDistance(100);
        EdgeIteratorState directSecond = graph.edge(1, 2).setDistance(100);
        EdgeIteratorState detourFirst = graph.edge(0, 3).setDistance(300);
        EdgeIteratorState detourSecond = graph.edge(3, 2).setDistance(300);

        Path baselineForward = route(graph, 0, 2, BlockedEdgeSnapshot.empty(), new SearchAudit());
        assertThat(edgeIds(baselineForward)).containsExactly(directFirst.getEdge(), directSecond.getEdge());

        BitSet forward = new BitSet();
        forward.set(directFirst.getEdge());
        SearchAudit forwardAudit = new SearchAudit();
        Path forwardBlocked = route(graph, 0, 2,
                new BlockedEdgeSnapshot(forward, new BitSet(), "test", "forward"), forwardAudit);
        assertThat(edgeIds(forwardBlocked)).containsExactly(detourFirst.getEdge(), detourSecond.getEdge());
        assertThat(forwardAudit.blockedRejections()).isPositive();

        Path reverseStillOpen = route(graph, 2, 0,
                new BlockedEdgeSnapshot(forward, new BitSet(), "test", "forward"), new SearchAudit());
        assertThat(edgeIds(reverseStillOpen)).contains(directFirst.getEdge());

        BitSet reverse = new BitSet();
        reverse.set(directFirst.getEdge());
        SearchAudit reverseAudit = new SearchAudit();
        Path reverseBlocked = route(graph, 2, 0,
                new BlockedEdgeSnapshot(new BitSet(), reverse, "test", "reverse"), reverseAudit);
        assertThat(edgeIds(reverseBlocked)).containsExactly(detourSecond.getEdge(), detourFirst.getEdge());
        assertThat(reverseAudit.blockedRejections()).isPositive();
    }

    @Test
    void queryGraphVirtualEdgesInheritTheBlockedBaseEdgeAndDirection() {
        BaseGraph baseGraph = graphWithNodes(2);
        EdgeIteratorState baseEdge = baseGraph.edge(0, 1).setDistance(1_000);
        Snap snap = edgeSnap(baseEdge, 0.0, 0.005);
        QueryGraph queryGraph = QueryGraph.create(baseGraph, snap);
        assertThat(snap.getClosestNode()).isGreaterThanOrEqualTo(baseGraph.getNodes());

        Path unblocked = route(queryGraph, snap.getClosestNode(), 1,
                BlockedEdgeSnapshot.empty(), new SearchAudit());
        assertThat(unblocked.isFound()).isTrue();
        assertThat(unblocked.calcEdges()).hasSize(1);

        BitSet forward = new BitSet();
        BitSet reverse = new BitSet();
        forward.set(baseEdge.getEdge());
        reverse.set(baseEdge.getEdge());
        SearchAudit audit = new SearchAudit();
        Path blocked = route(queryGraph, snap.getClosestNode(), 1,
                new BlockedEdgeSnapshot(forward, reverse, "test", "virtual-bidirectional"), audit);

        assertThat(audit.virtualEdgeChecks()).isPositive();
        assertThat(audit.blockedRejections()).isPositive();
        assertThat(blocked.calcEdges()).isEmpty();
        assertThat(blocked.isFound()).isFalse();
    }

    @Test
    void returnsNoPathWhenEveryReachableExitIsBlocked() {
        BaseGraph graph = graphWithNodes(4);
        EdgeIteratorState upperExit = graph.edge(0, 1).setDistance(100);
        graph.edge(1, 3).setDistance(100);
        EdgeIteratorState lowerExit = graph.edge(0, 2).setDistance(100);
        graph.edge(2, 3).setDistance(100);

        BitSet forward = new BitSet();
        forward.set(upperExit.getEdge());
        forward.set(lowerExit.getEdge());
        SearchAudit audit = new SearchAudit();
        Path path = route(graph, 0, 3,
                new BlockedEdgeSnapshot(forward, new BitSet(), "test", "all-exits"), audit);

        assertThat(path.isFound()).isFalse();
        assertThat(audit.edgeChecks()).isPositive();
        assertThat(audit.blockedRejections()).isPositive();
    }

    private static BaseGraph graphWithNodes(int count) {
        BaseGraph graph = new BaseGraph.Builder(4).create();
        for (int node = 0; node < count; node++) {
            graph.getNodeAccess().setNode(node, 0.0, node * 0.01);
        }
        return graph;
    }

    private static Snap edgeSnap(EdgeIteratorState edge, double lat, double lon) {
        Snap snap = new Snap(lat, lon);
        snap.setClosestEdge(edge);
        snap.setClosestNode(edge.getBaseNode());
        snap.setSnappedPosition(Snap.Position.EDGE);
        snap.setWayIndex(0);
        snap.setQueryDistance(0);
        snap.setSnappedPoint(new GHPoint3D(lat, lon, Double.NaN));
        return snap;
    }

    private static Path route(
            Graph graph,
            int from,
            int to,
            BlockedEdgeSnapshot snapshot,
            SearchAudit audit) {
        Weighting weighting = new BlockedEdgeWeighting(
                new DistanceWeighting(), snapshot, audit, graph.getBaseGraph().getEdges());
        RoutingAlgorithm algorithm = new RoutingAlgorithmFactorySimple().createAlgo(
                graph,
                weighting,
                new AlgorithmOptions().setAlgorithm(DIJKSTRA_BI).setTraversalMode(NODE_BASED));
        return algorithm.calcPath(from, to);
    }

    private static java.util.List<Integer> edgeIds(Path path) {
        return path.calcEdges().stream().map(EdgeIteratorState::getEdge).toList();
    }

    private static final class DistanceWeighting implements Weighting {
        @Override
        public double calcMinWeightPerDistance() {
            return 1;
        }

        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            return edgeState.getDistance();
        }

        @Override
        public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
            return Math.round(edgeState.getDistance());
        }

        @Override
        public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
            return 0;
        }

        @Override
        public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
            return 0;
        }

        @Override
        public boolean hasTurnCosts() {
            return false;
        }

        @Override
        public String getName() {
            return "distance";
        }
    }
}
