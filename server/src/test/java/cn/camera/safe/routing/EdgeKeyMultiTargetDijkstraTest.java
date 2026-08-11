package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.shapes.GHPoint3D;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.EXHAUSTED;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.MAX_VISITED_STATES;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.FORWARD;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.REVERSE;
import static org.assertj.core.api.Assertions.assertThat;

class EdgeKeyMultiTargetDijkstraTest {

    @Test
    void choosesDistanceBeforeTravelTimeAndStillReportsTravelTime() {
        BaseGraph graph = graph(3);
        EdgeIteratorState shortButSlow = graph.edge(0, 1).setDistance(100);
        EdgeIteratorState longButFast = graph.edge(0, 2).setDistance(1_000);

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                graph,
                new TimeByDistanceWeighting(),
                0,
                List.of(
                        portal("slow", shortButSlow, 1),
                        portal("fast", longButFast, 1)),
                FORWARD,
                0,
                100,
                Duration.ofSeconds(1));

        assertThat(result.minimumDistanceMeters()).isEqualTo(100);
        assertThat(result.candidates()).containsOnlyKeys("slow");
        assertThat(result.candidates().get("slow").travelTimeSeconds()).isEqualTo(100);
    }

    @Test
    void retainsOnlyPortalsWithinTheConfiguredDistanceTier() {
        BaseGraph graph = graph(4);
        EdgeIteratorState shortest = graph.edge(0, 1).setDistance(100);
        EdgeIteratorState withinTolerance = graph.edge(0, 2).setDistance(1_099);
        EdgeIteratorState outsideTolerance = graph.edge(0, 3).setDistance(1_101);

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                graph,
                new DistanceWeighting(),
                0,
                List.of(
                        portal("shortest", shortest, 1),
                        portal("within", withinTolerance, 1),
                        portal("outside", outsideTolerance, 1)),
                FORWARD,
                1_000,
                100,
                Duration.ofSeconds(1));

        assertThat(result.minimumDistanceMeters()).isEqualTo(100);
        assertThat(result.candidates()).containsOnlyKeys("shortest", "within");
        assertThat(result.candidates().get("within").distanceMeters()).isEqualTo(1_099);
        assertThat(result.provenNoRoute()).isFalse();
    }

    @Test
    void keepsArrivalEdgeStateSoARestrictedTurnCanUseTheLegalDetour() {
        BaseGraph graph = graph(4);
        EdgeIteratorState restrictedArrival = graph.edge(0, 1).setDistance(1);
        EdgeIteratorState detourFirst = graph.edge(0, 2).setDistance(1);
        EdgeIteratorState detourArrival = graph.edge(2, 1).setDistance(1);
        EdgeIteratorState exit = graph.edge(1, 3).setDistance(1);
        Weighting weighting = new RestrictedTurnDistanceWeighting(
                restrictedArrival.getEdge(), 1, exit.getEdge());

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                graph,
                weighting,
                0,
                List.of(portal("exit", exit, 1)),
                FORWARD,
                1_000,
                100,
                Duration.ofSeconds(1));

        assertThat(result.candidates().get("exit").edgeKeys()).containsExactly(
                detourFirst.getEdgeKey(), detourArrival.getEdgeKey(), exit.getEdgeKey());
        assertThat(result.minimumDistanceMeters()).isEqualTo(3);
    }

    @Test
    void reverseSearchCalculatesTurnsInForwardDrivingOrder() {
        BaseGraph graph = graph(4);
        EdgeIteratorState portalEdge = graph.edge(0, 1).setDistance(1);
        EdgeIteratorState restrictedExit = graph.edge(1, 3).setDistance(1);
        EdgeIteratorState legalFirst = graph.edge(1, 2).setDistance(1);
        EdgeIteratorState legalSecond = graph.edge(2, 3).setDistance(1);
        Weighting weighting = new RestrictedTurnDistanceWeighting(
                portalEdge.getEdge(), 1, restrictedExit.getEdge());

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                graph,
                weighting,
                3,
                List.of(portal("entry", portalEdge, 0.5)),
                REVERSE,
                1_000,
                100,
                Duration.ofSeconds(1));

        assertThat(result.minimumDistanceMeters()).isEqualTo(2.5);
        assertThat(result.candidates().get("entry").edgeKeys())
                .containsExactly(
                        portalEdge.getEdgeKey(), legalFirst.getEdgeKey(), legalSecond.getEdgeKey());
    }

    @Test
    void blockedDirectedEdgeIsRejectedDuringTheMultiTargetSearch() {
        BaseGraph graph = graph(4);
        EdgeIteratorState direct = graph.edge(0, 1).setDistance(1);
        EdgeIteratorState detourFirst = graph.edge(0, 2).setDistance(2);
        EdgeIteratorState detourSecond = graph.edge(2, 1).setDistance(2);
        EdgeIteratorState portalEdge = graph.edge(1, 3).setDistance(1);
        BitSet forward = new BitSet();
        forward.set(direct.getEdge());
        Weighting weighting = new BlockedEdgeWeighting(
                new DistanceWeighting(),
                new BlockedEdgeSnapshot(forward, new BitSet(), "camera", "blocked"),
                new SearchAudit(),
                graph.getEdges());

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                graph,
                weighting,
                0,
                List.of(portal("exit", portalEdge, 0)),
                FORWARD,
                1_000,
                100,
                Duration.ofSeconds(1));

        assertThat(result.minimumDistanceMeters()).isEqualTo(4);
        assertThat(result.candidates().get("exit").edgeKeys())
                .containsExactly(detourFirst.getEdgeKey(), detourSecond.getEdgeKey());
    }

    @Test
    void distinguishesExhaustedNoRouteFromResourceInterruption() {
        BaseGraph graph = graph(4);
        graph.edge(0, 1).setDistance(1);
        EdgeIteratorState unreachable = graph.edge(2, 3).setDistance(1);

        EdgeKeyMultiTargetDijkstra.SearchResult exhausted = EdgeKeyMultiTargetDijkstra.search(
                graph,
                new DistanceWeighting(),
                0,
                List.of(portal("missing", unreachable, 1)),
                FORWARD,
                1_000,
                100,
                Duration.ofSeconds(1));
        assertThat(exhausted.completion()).isEqualTo(EXHAUSTED);
        assertThat(exhausted.provenNoRoute()).isTrue();

        EdgeKeyMultiTargetDijkstra.SearchResult interrupted = EdgeKeyMultiTargetDijkstra.search(
                graph,
                new DistanceWeighting(),
                0,
                List.of(portal("missing", unreachable, 1)),
                FORWARD,
                1_000,
                1,
                Duration.ofSeconds(1));
        assertThat(interrupted.completion()).isEqualTo(MAX_VISITED_STATES);
        assertThat(interrupted.provenNoRoute()).isFalse();
    }

    @Test
    void findsAPortalOnTheOriginalEdgeFromAQueryGraphVirtualStart() {
        BaseGraph baseGraph = graph(2);
        EdgeIteratorState baseEdge = baseGraph.edge(0, 1).setDistance(1_000);
        Snap snap = new Snap(0, 0.005);
        snap.setClosestEdge(baseEdge);
        snap.setClosestNode(baseEdge.getBaseNode());
        snap.setSnappedPosition(Snap.Position.EDGE);
        snap.setWayIndex(0);
        snap.setQueryDistance(0);
        snap.setSnappedPoint(new GHPoint3D(0, 0.005, Double.NaN));
        QueryGraph queryGraph = QueryGraph.create(baseGraph, snap);

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                queryGraph,
                new DistanceWeighting(),
                snap.getClosestNode(),
                List.of(portal("exit", baseEdge, 1)),
                FORWARD,
                1_000,
                100,
                Duration.ofSeconds(1));

        assertThat(result.candidates()).containsOnlyKeys("exit");
        assertThat(result.minimumDistanceMeters()).isBetween(550.0, 560.0);
    }

    @Test
    void recordsAPortalPrefixButDoesNotContinueAcrossAForbiddenBoundaryEdge() {
        BaseGraph graph = graph(3);
        EdgeIteratorState crossing = graph.edge(0, 1).setDistance(100);
        EdgeIteratorState beyond = graph.edge(1, 2).setDistance(100);
        EdgeTraversalConstraint constraint = (edge, fraction) ->
                edge.getEdge() != crossing.getEdge() || fraction <= 0.5;

        EdgeKeyMultiTargetDijkstra.SearchResult result = EdgeKeyMultiTargetDijkstra.search(
                graph,
                new DistanceWeighting(),
                0,
                List.of(
                        portal("boundary", crossing, 0.5),
                        portal("forbidden", beyond, 1)),
                FORWARD,
                constraint,
                1_000,
                100,
                Duration.ofSeconds(1));

        assertThat(result.candidates()).containsOnlyKeys("boundary");
        assertThat(result.candidates().get("boundary").distanceMeters()).isEqualTo(50);
    }

    private static EdgeKeyMultiTargetDijkstra.Portal portal(
            String id,
            EdgeIteratorState edge,
            double fraction) {
        double baseLat = edge.getBaseNode() * 0.0;
        double baseLng = edge.getBaseNode() * 0.01;
        double adjacentLat = edge.getAdjNode() * 0.0;
        double adjacentLng = edge.getAdjNode() * 0.01;
        return new EdgeKeyMultiTargetDijkstra.Portal(
                id,
                edge.getEdgeKey(),
                fraction,
                new Wgs84Coordinate(
                        baseLng + (adjacentLng - baseLng) * fraction,
                        baseLat + (adjacentLat - baseLat) * fraction));
    }

    private static BaseGraph graph(int nodes) {
        BaseGraph graph = new BaseGraph.Builder(4).create();
        for (int node = 0; node < nodes; node++) {
            graph.getNodeAccess().setNode(node, 0, node * 0.01);
        }
        return graph;
    }

    private static class DistanceWeighting implements Weighting {
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

    private static final class RestrictedTurnDistanceWeighting extends DistanceWeighting {
        private final int restrictedInEdge;
        private final int restrictedViaNode;
        private final int restrictedOutEdge;

        private RestrictedTurnDistanceWeighting(
                int restrictedInEdge,
                int restrictedViaNode,
                int restrictedOutEdge) {
            this.restrictedInEdge = restrictedInEdge;
            this.restrictedViaNode = restrictedViaNode;
            this.restrictedOutEdge = restrictedOutEdge;
        }

        @Override
        public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
            return inEdge == restrictedInEdge
                    && viaNode == restrictedViaNode
                    && outEdge == restrictedOutEdge
                    ? Double.POSITIVE_INFINITY
                    : 0;
        }

        @Override
        public boolean hasTurnCosts() {
            return true;
        }
    }

    private static final class TimeByDistanceWeighting extends DistanceWeighting {
        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            return edgeState.getDistance() >= 1_000 ? 10 : 100;
        }
    }
}
