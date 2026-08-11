package cn.camera.safe.routing;

import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.util.BitSet;
import java.util.Objects;

/** Spatial hard constraint used while GraphHopper expands route edges. */
public final class BoundaryTraversalConstraint implements EdgeTraversalConstraint {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double BOUNDARY_TOLERANCE_DEGREES = 0.00001;
    private static final double FRACTION_EPSILON = 1e-9;

    private final PreparedGeometry preparedComparisonArea;
    private final Mode mode;
    private final int baseEdgeCount;
    private final BitSet evaluatedBaseEdges;
    private final BitSet allowedBaseEdges;

    private BoundaryTraversalConstraint(
            Geometry comparisonArea,
            Mode mode,
            int baseEdgeCount) {
        this.preparedComparisonArea = PreparedGeometryFactory.prepare(
                Objects.requireNonNull(comparisonArea));
        this.mode = Objects.requireNonNull(mode);
        if (baseEdgeCount < 0) {
            throw new IllegalArgumentException("base edge count must not be negative");
        }
        this.baseEdgeCount = baseEdgeCount;
        this.evaluatedBaseEdges = new BitSet(baseEdgeCount);
        this.allowedBaseEdges = new BitSet(baseEdgeCount);
    }

    public static BoundaryTraversalConstraint stayWithin(
            Geometry polygon,
            int baseEdgeCount) {
        Objects.requireNonNull(polygon, "polygon");
        return new BoundaryTraversalConstraint(
                polygon.buffer(BOUNDARY_TOLERANCE_DEGREES),
                Mode.STAY_WITHIN,
                baseEdgeCount);
    }

    public static BoundaryTraversalConstraint avoidInterior(
            Geometry polygon,
            int baseEdgeCount) {
        Objects.requireNonNull(polygon, "polygon");
        Geometry shrunken = polygon.buffer(-BOUNDARY_TOLERANCE_DEGREES);
        return new BoundaryTraversalConstraint(
                shrunken.isEmpty() ? polygon : shrunken,
                Mode.AVOID_INTERIOR,
                baseEdgeCount);
    }

    @Override
    public boolean allows(EdgeIteratorState edge, double fractionFromBase) {
        Objects.requireNonNull(edge, "edge");
        if (!Double.isFinite(fractionFromBase)
                || fractionFromBase < 0 || fractionFromBase > 1) {
            throw new IllegalArgumentException("edge fraction must be within [0, 1]");
        }
        if (fractionFromBase <= FRACTION_EPSILON) {
            return true;
        }
        if (fractionFromBase >= 1 - FRACTION_EPSILON) {
            return allowsFullEdge(edge);
        }

        LineString line = lineString(edge);
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double end = indexed.getStartIndex()
                + (indexed.getEndIndex() - indexed.getStartIndex()) * fractionFromBase;
        return allowsGeometry(indexed.extractLine(indexed.getStartIndex(), end));
    }

    private boolean allowsFullEdge(EdgeIteratorState edge) {
        int edgeId = edge.getEdge();
        if (edgeId < 0 || edgeId >= baseEdgeCount) {
            // QueryGraph reuses virtual edge ids between requests. Their geometry depends on
            // the snapped endpoints, so caching by edge id would be incorrect.
            return allowsGeometry(lineString(edge));
        }
        if (evaluatedBaseEdges.get(edgeId)) {
            return allowedBaseEdges.get(edgeId);
        }
        boolean allowed = allowsGeometry(lineString(edge));
        evaluatedBaseEdges.set(edgeId);
        if (allowed) {
            allowedBaseEdges.set(edgeId);
        }
        return allowed;
    }

    private boolean allowsGeometry(Geometry geometry) {
        return switch (mode) {
            case STAY_WITHIN -> preparedComparisonArea.covers(geometry);
            case AVOID_INTERIOR -> !preparedComparisonArea.intersects(geometry);
        };
    }

    private static LineString lineString(EdgeIteratorState edge) {
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        if (points.size() < 2) {
            throw new IllegalStateException("route edge geometry requires at least two points");
        }
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int index = 0; index < points.size(); index++) {
            coordinates[index] = new Coordinate(points.getLon(index), points.getLat(index));
        }
        return GEOMETRY_FACTORY.createLineString(coordinates);
    }

    private enum Mode {
        STAY_WITHIN,
        AVOID_INTERIOR
    }
}
