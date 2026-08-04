package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.Set;

import static cn.camera.safe.routing.SixthRingPortal.CandidateType.BOUNDARY_CHAIN_TRANSITION;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.BOUNDARY_NODE_TRANSITION;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.INTERIOR_EDGE;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.OVERLAP_EDGE_EXIT;
import static org.assertj.core.api.Assertions.assertThat;

class SixthRingPortalTopologyBuilderTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void preservesOneWayDirectionForAnInteriorEdgeCrossing() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.006, 0.005);
        node(fixture.graph(), 1, 0.010, 0.005);
        EdgeIteratorState crossing = edge(fixture, 0, 1, true, false);

        SixthRingPortalTopology topology = build(fixture, new LegalTurns());

        assertThat(topology.outbound().portals()).singleElement().satisfies(portal -> {
            assertThat(portal.edgeKey()).isEqualTo(crossing.getEdgeKey());
            assertThat(portal.candidateType()).isEqualTo(INTERIOR_EDGE);
            assertThat(portal.fractionFromBase()).isCloseTo(0.5, within(0.001));
        });
        assertThat(topology.inbound().portals()).isEmpty();
    }

    @Test
    void createsANodeTransitionOnlyWhenTheSourceSideCanReachTheBoundaryNode() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.006, 0.005);
        node(fixture.graph(), 1, 0.008, 0.005);
        node(fixture.graph(), 2, 0.010, 0.005);
        edge(fixture, 0, 1, true, false);
        EdgeIteratorState departure = edge(fixture, 1, 2, true, false);

        SixthRingPortalTopology topology = build(fixture, new LegalTurns());

        assertThat(topology.outbound().portals()).singleElement().satisfies(portal -> {
            assertThat(portal.edgeKey()).isEqualTo(departure.getEdgeKey());
            assertThat(portal.boundaryNode()).isEqualTo(1);
            assertThat(portal.fractionFromBase()).isZero();
            assertThat(portal.candidateType()).isEqualTo(BOUNDARY_NODE_TRANSITION);
        });
    }

    @Test
    void followsAOneWayBoundaryOverlapChainBeforeCreatingTheDeparturePortal() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.006, 0.004);
        node(fixture.graph(), 1, 0.008, 0.004);
        node(fixture.graph(), 2, 0.008, 0.006);
        node(fixture.graph(), 3, 0.010, 0.006);
        edge(fixture, 0, 1, true, false);
        edge(fixture, 1, 2, true, false);
        EdgeIteratorState departure = edge(fixture, 2, 3, true, false);

        SixthRingPortalTopology topology = build(fixture, new LegalTurns());

        assertThat(topology.outbound().portals()).singleElement().satisfies(portal -> {
            assertThat(portal.edgeKey()).isEqualTo(departure.getEdgeKey());
            assertThat(portal.boundaryNode()).isEqualTo(2);
            assertThat(portal.candidateType()).isEqualTo(BOUNDARY_CHAIN_TRANSITION);
        });
        assertThat(topology.outbound().overlappingEdges()).isEqualTo(1);
    }

    @Test
    void rejectsABoundaryNodeTransitionWhenItsTurnIsIllegal() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.006, 0.005);
        node(fixture.graph(), 1, 0.008, 0.005);
        node(fixture.graph(), 2, 0.010, 0.005);
        EdgeIteratorState approach = edge(fixture, 0, 1, true, false);
        EdgeIteratorState departure = edge(fixture, 1, 2, true, false);
        Weighting weighting = new RestrictedTurns(Set.of(
                new Turn(approach.getEdge(), 1, departure.getEdge())));

        SixthRingPortalTopology topology = build(fixture, weighting);

        assertThat(topology.outbound().portals()).isEmpty();
    }

    @Test
    void locatesTheActualExitPointWhenOneEdgeOverlapsTheBoundary() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.006, 0.004);
        node(fixture.graph(), 1, 0.010, 0.006);
        EdgeIteratorState crossing = edge(fixture, 0, 1, true, false);
        PointList pillars = new PointList(2, false);
        pillars.add(0.004, 0.008);
        pillars.add(0.006, 0.008);
        crossing.setWayGeometry(pillars);

        SixthRingPortalTopology topology = build(fixture, new LegalTurns());

        assertThat(topology.outbound().portals()).singleElement().satisfies(portal -> {
            assertThat(portal.candidateType()).isEqualTo(OVERLAP_EDGE_EXIT);
            assertThat(portal.crossing().lng()).isCloseTo(0.008, within(0.000001));
            assertThat(portal.crossing().lat()).isCloseTo(0.006, within(0.000001));
            assertThat(portal.fractionFromBase()).isCloseTo(2.0 / 3.0, within(0.001));
        });
    }

    @Test
    void deduplicatesTheSameDirectedDepartureReachedByAdjacentApproaches() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.006, 0.0045);
        node(fixture.graph(), 1, 0.006, 0.0055);
        node(fixture.graph(), 2, 0.008, 0.005);
        node(fixture.graph(), 3, 0.010, 0.005);
        edge(fixture, 0, 2, true, false);
        edge(fixture, 1, 2, true, false);
        EdgeIteratorState departure = edge(fixture, 2, 3, true, false);

        SixthRingPortalTopology topology = build(fixture, new LegalTurns());

        assertThat(topology.outbound().portals())
                .extracting(SixthRingPortal::edgeKey)
                .containsExactly(departure.getEdgeKey());
    }

    @Test
    void createsAnInboundPortalAtTheInnerBoundary() {
        GraphFixture fixture = graph();
        node(fixture.graph(), 0, 0.009, 0.005);
        node(fixture.graph(), 1, 0.007, 0.005);
        node(fixture.graph(), 2, 0.005, 0.005);
        edge(fixture, 0, 1, true, false);
        EdgeIteratorState entry = edge(fixture, 1, 2, true, false);

        SixthRingPortalTopology topology = build(fixture, new LegalTurns());

        assertThat(topology.inbound().portals()).singleElement().satisfies(portal -> {
            assertThat(portal.edgeKey()).isEqualTo(entry.getEdgeKey());
            assertThat(portal.boundaryNode()).isEqualTo(1);
            assertThat(portal.candidateType()).isEqualTo(BOUNDARY_NODE_TRANSITION);
        });
    }

    private static SixthRingPortalTopology build(GraphFixture fixture, Weighting weighting) {
        return new SixthRingPortalTopologyBuilder().build(
                fixture.graph(), fixture.access(), weighting, boundary());
    }

    private static SixthRingBoundary boundary() {
        Polygon inner = square(0.003, 0.007);
        Polygon outer = square(0.002, 0.008);
        return new SixthRingBoundary(inner, outer, "test-boundary-v1");
    }

    private static Polygon square(double minimum, double maximum) {
        return GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minimum, minimum),
                new Coordinate(maximum, minimum),
                new Coordinate(maximum, maximum),
                new Coordinate(minimum, maximum),
                new Coordinate(minimum, minimum)
        });
    }

    private static GraphFixture graph() {
        BooleanEncodedValue access = VehicleAccess.create("car");
        EncodingManager encodingManager = EncodingManager.start().add(access).build();
        return new GraphFixture(new BaseGraph.Builder(encodingManager).create(), access);
    }

    private static void node(BaseGraph graph, int node, double lng, double lat) {
        graph.getNodeAccess().setNode(node, lat, lng);
    }

    private static EdgeIteratorState edge(
            GraphFixture fixture,
            int base,
            int adjacent,
            boolean forward,
            boolean reverse) {
        return fixture.graph().edge(base, adjacent)
                .setDistance(100)
                .set(fixture.access(), forward, reverse);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private record GraphFixture(BaseGraph graph, BooleanEncodedValue access) {
    }

    private record Turn(int incomingEdge, int viaNode, int outgoingEdge) {
    }

    private static class LegalTurns implements Weighting {
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
            return true;
        }

        @Override
        public String getName() {
            return "legal-turns";
        }
    }

    private static final class RestrictedTurns extends LegalTurns {
        private final Set<Turn> restricted;

        private RestrictedTurns(Set<Turn> restricted) {
            this.restricted = Set.copyOf(restricted);
        }

        @Override
        public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
            return restricted.contains(new Turn(inEdge, viaNode, outEdge))
                    ? Double.POSITIVE_INFINITY : 0;
        }
    }
}
