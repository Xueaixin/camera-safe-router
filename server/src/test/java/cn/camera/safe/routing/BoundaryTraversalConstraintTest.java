package cn.camera.safe.routing;

import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundaryTraversalConstraintTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void stayWithinRejectsEdgesThatLeaveTheBoundary() {
        Polygon polygon = square();
        BoundaryTraversalConstraint constraint =
                BoundaryTraversalConstraint.stayWithin(polygon, 2);

        assertThat(constraint.allows(edge(0, pointList(2, 2, 8, 8)))).isTrue();
        assertThat(constraint.allows(edge(1, pointList(2, 2, 12, 8)))).isFalse();
    }

    @Test
    void avoidInteriorAllowsExternalEdgesAndRejectsInteriorEdges() {
        Polygon polygon = square();
        BoundaryTraversalConstraint constraint =
                BoundaryTraversalConstraint.avoidInterior(polygon, 2);

        assertThat(constraint.allows(edge(0, pointList(12, 2, 12, 8)))).isTrue();
        assertThat(constraint.allows(edge(1, pointList(2, 2, 8, 8)))).isFalse();
    }

    @Test
    void doesNotReuseCachedResultsForQueryGraphVirtualEdges() {
        Polygon polygon = square();
        BoundaryTraversalConstraint constraint =
                BoundaryTraversalConstraint.avoidInterior(polygon, 1);

        assertThat(constraint.allows(edge(1, pointList(12, 2, 12, 8)))).isTrue();
        assertThat(constraint.allows(edge(1, pointList(2, 2, 8, 8)))).isFalse();
    }

    private static Polygon square() {
        return GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(0, 0),
                new Coordinate(10, 0),
                new Coordinate(10, 10),
                new Coordinate(0, 10),
                new Coordinate(0, 0)
        });
    }

    private static EdgeIteratorState edge(int edgeId, PointList geometry) {
        EdgeIteratorState edge = mock(EdgeIteratorState.class);
        when(edge.getEdge()).thenReturn(edgeId);
        when(edge.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL))
                .thenReturn(geometry);
        return edge;
    }

    private static PointList pointList(double lng1, double lat1, double lng2, double lat2) {
        PointList points = new PointList(2, false);
        points.add(lat1, lng1);
        points.add(lat2, lng2);
        return points;
    }
}
