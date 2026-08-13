package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.util.BitSet;
import java.util.List;

import static cn.camera.safe.routing.SixthRingPortal.BoundaryRole.INNER_ENTRY;
import static cn.camera.safe.routing.SixthRingPortal.BoundaryRole.OUTER_EXIT;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.INTERIOR_EDGE;
import static cn.camera.safe.routing.SixthRingPortal.Direction.INBOUND;
import static cn.camera.safe.routing.SixthRingPortal.Direction.OUTBOUND;
import static org.assertj.core.api.Assertions.assertThat;

class ControlReleaseTopologyBuilderTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

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
                graph, access, emptyBoundaryTopology(), classification, null, 50);

        assertThat(topology.outbound()).isEmpty();
        assertThat(topology.inbound()).isEmpty();
    }

    @Test
    void excludesInboundOrdinaryPortalOnProvincialBorderAndKeepsOutbound() {
        BooleanEncodedValue access = VehicleAccess.create("car");
        EncodingManager encodingManager = EncodingManager.start().add(access).build();
        BaseGraph graph = new BaseGraph.Builder(encodingManager).create();
        RoadClassificationIndex classification = new RoadClassificationIndex(
                new BitSet(), new BitSet(), new BitSet(), "test");

        SixthRingPortal inboundOnBorder = portal(
                "inbound-on-border", 1, INBOUND, INNER_ENTRY, 116.84398, 39.66338);
        SixthRingPortal inboundOffBorder = portal(
                "inbound-off-border", 2, INBOUND, INNER_ENTRY, 116.3456, 40.1653);
        SixthRingPortal outboundOnBorder = portal(
                "outbound-on-border", 3, OUTBOUND, OUTER_EXIT, 116.84398, 39.66338);
        SixthRingPortalTopology topology = topologyWith(
                List.of(outboundOnBorder), List.of(inboundOnBorder, inboundOffBorder));
        LineString provincialBorder = GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                new Coordinate(116.8435, 39.6630),
                new Coordinate(116.8445, 39.6638)
        });

        ControlReleaseTopology release = new ControlReleaseTopologyBuilder().build(
                graph, access, topology, classification, provincialBorder, 50);

        assertThat(release.outbound()).extracting(ControlReleasePoint::id)
                .containsExactly("ordinary:outbound-on-border");
        assertThat(release.inbound()).extracting(ControlReleasePoint::id)
                .containsExactly("ordinary:inbound-off-border");
    }

    @Test
    void keepsAllInboundOrdinaryPortalsWithoutProvincialBorderData() {
        BooleanEncodedValue access = VehicleAccess.create("car");
        EncodingManager encodingManager = EncodingManager.start().add(access).build();
        BaseGraph graph = new BaseGraph.Builder(encodingManager).create();
        RoadClassificationIndex classification = new RoadClassificationIndex(
                new BitSet(), new BitSet(), new BitSet(), "test");
        SixthRingPortal inboundOnBorder = portal(
                "inbound-on-border", 1, INBOUND, INNER_ENTRY, 116.84398, 39.66338);
        SixthRingPortalTopology topology = topologyWith(List.of(), List.of(inboundOnBorder));

        ControlReleaseTopology release = new ControlReleaseTopologyBuilder().build(
                graph, access, topology, classification, null, 50);

        assertThat(release.inbound()).extracting(ControlReleasePoint::id)
                .containsExactly("ordinary:inbound-on-border");
    }

    @Test
    void onProvincialBorderUsesToleranceMeters() {
        SixthRingPortal portal = portal(
                "border", 1, INBOUND, INNER_ENTRY, 116.84398, 39.66338);
        LineString line = GEOMETRY_FACTORY.createLineString(new Coordinate[] {
                new Coordinate(116.8435, 39.6630),
                new Coordinate(116.8445, 39.6638)
        });

        assertThat(ControlReleaseTopologyBuilder.onProvincialBorder(portal, line, 50))
                .isTrue();
        assertThat(ControlReleaseTopologyBuilder.onProvincialBorder(portal, line, 0))
                .isFalse();
        assertThat(ControlReleaseTopologyBuilder.onProvincialBorder(portal, null, 50))
                .isFalse();
    }

    private static SixthRingPortal portal(
            String id,
            int edgeId,
            SixthRingPortal.Direction direction,
            SixthRingPortal.BoundaryRole role,
            double lng,
            double lat) {
        return new SixthRingPortal(
                id,
                edgeId,
                edgeId * 2,
                direction,
                role,
                INTERIOR_EDGE,
                -1,
                0.5,
                new Wgs84Coordinate(lng, lat),
                "test-road");
    }

    private static SixthRingPortalTopology topologyWith(
            List<SixthRingPortal> outbound,
            List<SixthRingPortal> inbound) {
        return new SixthRingPortalTopology(
                "test-boundary",
                new SixthRingPortalTopology.Scan(
                        OUTER_EXIT, OUTBOUND, 0, 0, 0, 0, 0, 0, outbound),
                new SixthRingPortalTopology.Scan(
                        INNER_ENTRY, INBOUND, 0, 0, 0, 0, 0, 0, inbound));
    }

    private static SixthRingPortalTopology emptyBoundaryTopology() {
        return topologyWith(List.of(), List.of());
    }
}
