package cn.camera.safe.routing;

import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoadStateTraversalConstraintTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void enforcesSixthRingHighwayAndDirectedConnectorRules() {
        BitSet exempt = bits(1);
        BitSet allHighways = bits(1, 4);
        BitSet sixthRing = bits(1);
        BitSet forbiddenHighways = bits(4);
        BitSet links = bits(2, 5);
        BitSet sixthInterior = bits(0, 1, 2, 4);
        BitSet tongzhou = bits(5);
        BitSet connectors = bits(2, 5);
        BitSet sixthExitKeys = bits(4);
        BitSet tongzhouConnectors = bits(5);
        RoadClassificationIndex classification = new RoadClassificationIndex(
                exempt, allHighways, sixthRing, new BitSet(), forbiddenHighways,
                links, sixthInterior, tongzhou, connectors,
                sixthExitKeys, tongzhouConnectors, "test");
        RoadStateTraversalConstraint controlled = RoadStateTraversalConstraint.controlled(
                square(), 6, classification);
        RoadStateTraversalConstraint released = RoadStateTraversalConstraint.released(
                square(), 6, classification);

        EdgeIteratorState insideRoad = edge(0, 2, 2, 8, 8);
        EdgeIteratorState highwayInside = edge(1, 2, 2, 8, 8);
        EdgeIteratorState connectorInside = edge(2, 2, 2, 8, 8);
        EdgeIteratorState outsideRoad = edge(3, 12, 2, 12, 8);
        EdgeIteratorState forbiddenHighway = edge(4, 2, 3, 8, 3);
        EdgeIteratorState tongzhouConnector = edge(5, 2, 4, 8, 4);

        assertThat(controlled.allows(insideRoad)).isTrue();
        assertThat(controlled.allows(highwayInside, 0)).isFalse();
        assertThat(controlled.allows(highwayInside)).isFalse();
        assertThat(released.allows(highwayInside)).isTrue();
        assertThat(released.allows(connectorInside)).isTrue();
        assertThat(released.allows(connectorInside, true)).isFalse();
        assertThat(released.allows(tongzhouConnector)).isTrue();
        assertThat(released.allows(forbiddenHighway)).isFalse();
        assertThat(released.allows(insideRoad)).isFalse();
        assertThat(released.allows(outsideRoad)).isTrue();
    }

    @Test
    void releasedPhaseAllowsOnlyTheVerifiedDirectedTollCorridorKeys() {
        BitSet exempt = bits(1);
        BitSet allHighways = bits(1);
        BitSet sixthRing = bits(1);
        BitSet links = bits(2);
        BitSet sixthInterior = bits(0, 1, 2);
        BitSet entryKeys = bits(4);
        RoadClassificationIndex classification = new RoadClassificationIndex(
                exempt, allHighways, sixthRing, new BitSet(), new BitSet(),
                links, sixthInterior, new BitSet(), new BitSet(),
                new BitSet(), entryKeys, new BitSet(), new BitSet(), "corridor-test");
        RoadStateTraversalConstraint released = RoadStateTraversalConstraint.released(
                square(), 6, classification);

        EdgeIteratorState entryCorridorInside = edge(2, 2, 2, 8, 8);
        EdgeIteratorState unrelatedInteriorLink = edge(3, 2, 2, 8, 8);

        assertThat(released.allows(entryCorridorInside)).isTrue();
        assertThat(released.allows(entryCorridorInside, true)).isFalse();
        assertThat(released.allows(unrelatedInteriorLink)).isFalse();
    }

    private static org.locationtech.jts.geom.Polygon square() {
        return GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(10, 10), new Coordinate(0, 10),
                new Coordinate(0, 0)
        });
    }

    private static EdgeIteratorState edge(
            int edgeId,
            double lng1,
            double lat1,
            double lng2,
            double lat2) {
        EdgeIteratorState edge = mock(EdgeIteratorState.class);
        when(edge.getEdge()).thenReturn(edgeId);
        when(edge.getEdgeKey()).thenReturn(edgeId * 2);
        PointList points = new PointList(2, false);
        points.add(lat1, lng1);
        points.add(lat2, lng2);
        when(edge.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL)).thenReturn(points);
        return edge;
    }

    private static BitSet bits(int... values) {
        BitSet result = new BitSet();
        for (int value : values) {
            result.set(value);
        }
        return result;
    }
}
