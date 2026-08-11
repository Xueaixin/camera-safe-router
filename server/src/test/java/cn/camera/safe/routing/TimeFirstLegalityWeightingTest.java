package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeFirstLegalityWeightingTest {

    @Test
    void usesRealTravelSecondsWhilePreservingHardLegality() {
        BaseGraph graph = new BaseGraph.Builder(4).create();
        graph.getNodeAccess().setNode(0, 0, 0);
        graph.getNodeAccess().setNode(1, 0, 0.01);
        EdgeIteratorState legal = graph.edge(0, 1).setDistance(100);
        Weighting weighting = new TimeFirstLegalityWeighting(new DelegateWeighting());

        assertThat(weighting.calcEdgeWeight(legal, false)).isEqualTo(12);
        assertThat(weighting.calcTurnWeight(1, 2, 3)).isEqualTo(2);
        assertThat(weighting.calcTurnWeight(7, 2, 3)).isInfinite();

        legal.setDistance(999);
        assertThat(weighting.calcEdgeWeight(legal, false)).isInfinite();
    }

    private static final class DelegateWeighting implements Weighting {
        @Override
        public double calcMinWeightPerDistance() {
            return 0;
        }

        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            return edgeState.getDistance() == 999 ? Double.POSITIVE_INFINITY : 7;
        }

        @Override
        public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
            return 12_000;
        }

        @Override
        public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
            return inEdge == 7 ? 1_000_000_000 : 5;
        }

        @Override
        public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
            return 2_000;
        }

        @Override
        public boolean hasTurnCosts() {
            return true;
        }

        @Override
        public String getName() {
            return "delegate";
        }
    }
}
