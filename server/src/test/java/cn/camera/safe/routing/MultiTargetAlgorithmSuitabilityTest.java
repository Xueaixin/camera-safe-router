package cn.camera.safe.routing;

import com.graphhopper.routing.Dijkstra;
import com.graphhopper.routing.DijkstraOneToMany;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiTargetAlgorithmSuitabilityTest {

    @Test
    void builtInNodeStateOneToManyCannotRepresentTurnRestrictedAlternatives() {
        BaseGraph graph = new BaseGraph.Builder(4).withTurnCosts(true).create();
        for (int node = 0; node < 4; node++) {
            graph.getNodeAccess().setNode(node, 0, node * 0.01);
        }
        EdgeIteratorState restrictedArrival = graph.edge(0, 1).setDistance(1);
        EdgeIteratorState detourFirst = graph.edge(0, 2).setDistance(1);
        EdgeIteratorState detourArrival = graph.edge(2, 1).setDistance(1);
        EdgeIteratorState exit = graph.edge(1, 3).setDistance(1);
        Weighting weighting = new RestrictedTurnDistanceWeighting(
                restrictedArrival.getEdge(), 1, exit.getEdge());

        Path compliant = new Dijkstra(graph, weighting, TraversalMode.EDGE_BASED)
                .calcPath(0, 3);
        assertThat(compliant.isFound()).isTrue();
        assertThat(compliant.calcEdges().stream().map(EdgeIteratorState::getEdge).toList())
                .containsExactly(detourFirst.getEdge(), detourArrival.getEdge(), exit.getEdge());

        assertThatThrownBy(() -> new DijkstraOneToMany(
                graph, weighting, TraversalMode.NODE_BASED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turn costs")
                .hasMessageContaining("node-based");

        // DijkstraOneToMany stores one state per node. Its edge-based mode does not initialize
        // the source state correctly, so it must not be executed as a turn-aware fallback.
    }

    private record RestrictedTurnDistanceWeighting(
            int restrictedInEdge,
            int restrictedViaNode,
            int restrictedOutEdge) implements Weighting {

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
            return inEdge == restrictedInEdge
                    && viaNode == restrictedViaNode
                    && outEdge == restrictedOutEdge
                    ? Double.POSITIVE_INFINITY
                    : 0;
        }

        @Override
        public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
            return Double.isInfinite(calcTurnWeight(inEdge, viaNode, outEdge)) ? Long.MAX_VALUE : 0;
        }

        @Override
        public boolean hasTurnCosts() {
            return true;
        }

        @Override
        public String getName() {
            return "turn-restricted-distance";
        }
    }
}
