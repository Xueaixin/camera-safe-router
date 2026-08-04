package cn.camera.safe.routing;

import com.graphhopper.util.exceptions.ConnectionNotFoundException;
import com.graphhopper.util.exceptions.MaximumNodesExceededException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphHopperRoutingEngineTest {

    @Test
    void distinguishesSearchLimitFromAProvenMissingRoute() {
        assertThat(GraphHopperRoutingEngine.classifyErrors(List.of(
                new MaximumNodesExceededException("limit", 100))))
                .isEqualTo(RoutingEngineException.Reason.RESOURCE_LIMIT);
        assertThat(GraphHopperRoutingEngine.classifyErrors(List.of(
                new ConnectionNotFoundException("none", java.util.Map.of()))))
                .isEqualTo(RoutingEngineException.Reason.NO_ROUTE);
    }
}
