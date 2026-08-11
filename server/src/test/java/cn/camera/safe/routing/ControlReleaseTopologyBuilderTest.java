package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControlReleaseTopologyBuilderTest {
    @Test
    void doesNotCreateControlledAreaHighwayReleaseTargets() {
        BooleanEncodedValue access = VehicleAccess.create("car");
        EncodingManager encodingManager = EncodingManager.start().add(access).build();
        BaseGraph graph = new BaseGraph.Builder(encodingManager).create();
        graph.getNodeAccess().setNode(0, 1, 2);
        graph.getNodeAccess().setNode(1, 3, 4);
        EdgeIteratorState edge = graph.edge(0, 1)
                .setDistance(100)
                .set(access, true, true);
        BitSet highway = new BitSet();
        highway.set(edge.getEdge());
        RoadClassificationIndex classification = new RoadClassificationIndex(
                highway, new BitSet(), new BitSet(), "test");

        ControlReleaseTopology topology = new ControlReleaseTopologyBuilder().build(
                graph, access, emptyBoundaryTopology(), classification);

        assertThat(topology.outbound()).isEmpty();
        assertThat(topology.inbound()).isEmpty();
    }

    private static SixthRingPortalTopology emptyBoundaryTopology() {
        return new SixthRingPortalTopology(
                "test-boundary",
                new SixthRingPortalTopology.Scan(
                        SixthRingPortal.BoundaryRole.OUTER_EXIT,
                        SixthRingPortal.Direction.OUTBOUND,
                        0, 0, 0, 0, 0, 0, List.of()),
                new SixthRingPortalTopology.Scan(
                        SixthRingPortal.BoundaryRole.INNER_ENTRY,
                        SixthRingPortal.Direction.INBOUND,
                        0, 0, 0, 0, 0, 0, List.of()));
    }
}
