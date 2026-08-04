package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static cn.camera.safe.routing.SixthRingPortal.BoundaryRole.INNER_ENTRY;
import static cn.camera.safe.routing.SixthRingPortal.BoundaryRole.OUTER_EXIT;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.BOUNDARY_CHAIN_TRANSITION;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.BOUNDARY_NODE_TRANSITION;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.INTERIOR_EDGE;
import static cn.camera.safe.routing.SixthRingPortal.CandidateType.OVERLAP_EDGE_EXIT;
import static cn.camera.safe.routing.SixthRingPortal.Direction.INBOUND;
import static cn.camera.safe.routing.SixthRingPortal.Direction.OUTBOUND;

public final class SixthRingPortalTopologyBuilder {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double BOUNDARY_TOLERANCE_DEGREES = 0.00001;
    private static final double INDEX_EPSILON = 1e-10;
    private static final long FRACTION_SCALE = 1_000_000_000L;

    public SixthRingPortalTopology build(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            Weighting weighting,
            SixthRingBoundary boundary) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(carAccess, "carAccess");
        Objects.requireNonNull(weighting, "weighting");
        Objects.requireNonNull(boundary, "boundary");

        SixthRingPortalTopology.Scan outbound = scanBoundary(
                graph, carAccess, weighting, boundary.outerPolygon(), OUTBOUND, OUTER_EXIT);
        SixthRingPortalTopology.Scan inbound = scanBoundary(
                graph, carAccess, weighting, boundary.innerPolygon(), INBOUND, INNER_ENTRY);
        return new SixthRingPortalTopology(boundary.version(), outbound, inbound);
    }

    private static SixthRingPortalTopology.Scan scanBoundary(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            Weighting weighting,
            Polygon boundary,
            SixthRingPortal.Direction direction,
            SixthRingPortal.BoundaryRole boundaryRole) {
        STRtree boundarySegments = boundarySegmentIndex(boundary);
        Map<PortalKey, SixthRingPortal> portals = new LinkedHashMap<>();
        Set<Integer> boundaryNodes = new HashSet<>();
        int intersectingEdges = 0;
        int overlappingEdges = 0;
        int ambiguousEdges = 0;
        int geometryCrossings = 0;

        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            boolean forward = edges.get(carAccess);
            boolean reverse = edges.getReverse(carAccess);
            if (!forward && !reverse) {
                continue;
            }
            PointList points = edges.fetchWayGeometry(FetchMode.ALL);
            if (points.size() < 2 || boundarySegments.query(envelope(points)).isEmpty()) {
                continue;
            }
            LineString line = lineString(points);
            Geometry intersection = line.intersection(boundary.getBoundary());
            if (intersection.isEmpty()) {
                continue;
            }

            intersectingEdges++;
            geometryCrossings += distinctCoordinateCount(intersection.getCoordinates());
            if (intersection.getDimension() > 0) {
                overlappingEdges++;
            }
            collectBoundaryEndpointNodes(graph, boundary, edges, boundaryNodes);

            int before = portals.size();
            if (forward) {
                addTraversalPortals(
                        portals, edges.getEdge(), edges.getEdgeKey(), edges.getName(), line,
                        boundary, direction, boundaryRole);
            }
            if (reverse) {
                addTraversalPortals(
                        portals, edges.getEdge(), edges.getReverseEdgeKey(), edges.getName(),
                        reverse(line), boundary, direction, boundaryRole);
            }
            if (before == portals.size()
                    && !isBoundaryNode(graph, boundary, edges.getBaseNode())
                    && !isBoundaryNode(graph, boundary, edges.getAdjNode())) {
                ambiguousEdges++;
            }
        }

        int beforeNodeTransitions = portals.size();
        addBoundaryNodeTransitions(
                graph, carAccess, weighting, boundary, direction, boundaryRole,
                boundaryNodes, portals);
        int nodeTransitionCandidates = portals.size() - beforeNodeTransitions;
        List<SixthRingPortal> sorted = portals.values().stream()
                .sorted(Comparator
                        .comparingDouble((SixthRingPortal portal) -> portal.crossing().lng())
                        .thenComparingDouble(portal -> portal.crossing().lat())
                        .thenComparingInt(SixthRingPortal::edgeKey)
                        .thenComparingDouble(SixthRingPortal::fractionFromBase))
                .toList();
        return new SixthRingPortalTopology.Scan(
                boundaryRole,
                direction,
                intersectingEdges,
                overlappingEdges,
                ambiguousEdges,
                geometryCrossings,
                boundaryNodes.size(),
                nodeTransitionCandidates,
                sorted);
    }

    private static void addTraversalPortals(
            Map<PortalKey, SixthRingPortal> portals,
            int edgeId,
            int edgeKey,
            String roadName,
            LineString traversal,
            Polygon boundary,
            SixthRingPortal.Direction direction,
            SixthRingPortal.BoundaryRole boundaryRole) {
        Side source = direction == OUTBOUND ? Side.INSIDE : Side.OUTSIDE;
        Side target = direction == OUTBOUND ? Side.OUTSIDE : Side.INSIDE;
        List<SideInterval> intervals = sideIntervals(traversal, boundary);
        boolean sourceSeen = false;
        boolean boundaryAfterSource = false;
        for (SideInterval interval : intervals) {
            if (interval.side() == source) {
                sourceSeen = true;
                boundaryAfterSource = false;
                continue;
            }
            if (interval.side() == Side.BOUNDARY) {
                boundaryAfterSource |= sourceSeen;
                continue;
            }
            if (interval.side() != target || !sourceSeen) {
                continue;
            }

            double fraction = fraction(traversal, interval.startIndex());
            Coordinate coordinate = pointAt(traversal, interval.startIndex());
            SixthRingPortal.CandidateType candidateType = boundaryAfterSource
                    ? OVERLAP_EDGE_EXIT : INTERIOR_EDGE;
            putPortal(
                    portals,
                    edgeId,
                    edgeKey,
                    direction,
                    boundaryRole,
                    candidateType,
                    -1,
                    fraction,
                    coordinate,
                    roadName);
            sourceSeen = false;
            boundaryAfterSource = false;
        }
    }

    private static void addBoundaryNodeTransitions(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            Weighting weighting,
            Polygon boundary,
            SixthRingPortal.Direction direction,
            SixthRingPortal.BoundaryRole boundaryRole,
            Set<Integer> boundaryNodes,
            Map<PortalKey, SixthRingPortal> portals) {
        Side source = direction == OUTBOUND ? Side.INSIDE : Side.OUTSIDE;
        Side target = direction == OUTBOUND ? Side.OUTSIDE : Side.INSIDE;
        EdgeExplorer explorer = graph.createEdgeExplorer();
        Deque<BoundaryState> queue = new ArrayDeque<>();
        Map<BoundaryStateKey, Integer> bestBoundaryHops = new HashMap<>();

        for (int node : boundaryNodes.stream().sorted().toList()) {
            EdgeIterator edge = explorer.setBaseNode(node);
            while (edge.next()) {
                if (!edge.getReverse(carAccess)) {
                    continue;
                }
                DirectedEdgeSide edgeSide = classifyFromBase(
                        edge.fetchWayGeometry(FetchMode.ALL), boundary);
                if (edgeSide.firstNonBoundarySide() == source) {
                    enqueueBoundaryState(
                            queue,
                            bestBoundaryHops,
                            new BoundaryState(node, edge.getEdge(), 0));
                }
            }
        }

        while (!queue.isEmpty()) {
            BoundaryState state = queue.removeFirst();
            EdgeIterator edge = explorer.setBaseNode(state.node());
            while (edge.next()) {
                if (!edge.get(carAccess)
                        || !isLegalTurn(weighting, state.incomingEdgeId(), state.node(), edge.getEdge())) {
                    continue;
                }
                PointList points = edge.fetchWayGeometry(FetchMode.ALL);
                DirectedEdgeSide edgeSide = classifyFromBase(points, boundary);
                if (edgeSide.firstNonBoundarySide() == target) {
                    SixthRingPortal.CandidateType candidateType;
                    if (state.boundaryHops() > 0) {
                        candidateType = BOUNDARY_CHAIN_TRANSITION;
                    } else if (edgeSide.firstNonBoundaryFraction() > INDEX_EPSILON) {
                        candidateType = OVERLAP_EDGE_EXIT;
                    } else {
                        candidateType = BOUNDARY_NODE_TRANSITION;
                    }
                    LineString line = lineString(points);
                    Coordinate coordinate = pointAt(
                            line,
                            line.getLength() * edgeSide.firstNonBoundaryFraction());
                    putPortal(
                            portals,
                            edge.getEdge(),
                            edge.getEdgeKey(),
                            direction,
                            boundaryRole,
                            candidateType,
                            state.node(),
                            edgeSide.firstNonBoundaryFraction(),
                            coordinate,
                            edge.getName());
                    continue;
                }
                if (edgeSide.firstNonBoundarySide() == null
                        && boundaryNodes.contains(edge.getAdjNode())) {
                    enqueueBoundaryState(
                            queue,
                            bestBoundaryHops,
                            new BoundaryState(
                                    edge.getAdjNode(), edge.getEdge(), state.boundaryHops() + 1));
                }
            }
        }
    }

    private static void enqueueBoundaryState(
            Deque<BoundaryState> queue,
            Map<BoundaryStateKey, Integer> bestBoundaryHops,
            BoundaryState candidate) {
        BoundaryStateKey key = new BoundaryStateKey(
                candidate.node(), candidate.incomingEdgeId());
        Integer existing = bestBoundaryHops.get(key);
        if (existing == null || candidate.boundaryHops() < existing) {
            bestBoundaryHops.put(key, candidate.boundaryHops());
            queue.addLast(candidate);
        }
    }

    private static boolean isLegalTurn(
            Weighting weighting,
            int incomingEdgeId,
            int viaNode,
            int outgoingEdgeId) {
        double turnWeight = weighting.calcTurnWeight(
                incomingEdgeId, viaNode, outgoingEdgeId);
        return Double.isFinite(turnWeight) && turnWeight >= 0;
    }

    private static DirectedEdgeSide classifyFromBase(PointList points, Polygon boundary) {
        if (points.size() < 2) {
            return new DirectedEdgeSide(null, 0);
        }
        LineString line = lineString(points);
        for (SideInterval interval : sideIntervals(line, boundary)) {
            if (interval.side() != Side.BOUNDARY) {
                return new DirectedEdgeSide(
                        interval.side(), fraction(line, interval.startIndex()));
            }
        }
        return new DirectedEdgeSide(null, 0);
    }

    private static List<SideInterval> sideIntervals(LineString line, Polygon boundary) {
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double start = indexed.getStartIndex();
        double end = indexed.getEndIndex();
        if (end - start <= INDEX_EPSILON) {
            return List.of();
        }

        TreeSet<Double> indexes = new TreeSet<>();
        indexes.add(start);
        indexes.add(end);
        Geometry intersection = line.intersection(boundary.getBoundary());
        for (Coordinate coordinate : intersection.getCoordinates()) {
            indexes.add(Math.max(start, Math.min(end, indexed.project(coordinate))));
        }

        List<Double> ordered = List.copyOf(indexes);
        List<SideInterval> intervals = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            double intervalStart = ordered.get(index - 1);
            double intervalEnd = ordered.get(index);
            if (intervalEnd - intervalStart <= INDEX_EPSILON) {
                continue;
            }
            Point sample = GEOMETRY_FACTORY.createPoint(
                    indexed.extractPoint((intervalStart + intervalEnd) / 2));
            intervals.add(new SideInterval(
                    intervalStart, intervalEnd, classify(sample, boundary)));
        }
        return List.copyOf(intervals);
    }

    private static Side classify(Point point, Polygon boundary) {
        if (boundary.getBoundary().isWithinDistance(point, BOUNDARY_TOLERANCE_DEGREES)) {
            return Side.BOUNDARY;
        }
        return boundary.contains(point) ? Side.INSIDE : Side.OUTSIDE;
    }

    private static void collectBoundaryEndpointNodes(
            BaseGraph graph,
            Polygon boundary,
            EdgeIteratorState edge,
            Set<Integer> boundaryNodes) {
        if (isBoundaryNode(graph, boundary, edge.getBaseNode())) {
            boundaryNodes.add(edge.getBaseNode());
        }
        if (isBoundaryNode(graph, boundary, edge.getAdjNode())) {
            boundaryNodes.add(edge.getAdjNode());
        }
    }

    private static boolean isBoundaryNode(BaseGraph graph, Polygon boundary, int node) {
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(
                graph.getNodeAccess().getLon(node),
                graph.getNodeAccess().getLat(node)));
        return boundary.getBoundary().isWithinDistance(point, BOUNDARY_TOLERANCE_DEGREES);
    }

    private static void putPortal(
            Map<PortalKey, SixthRingPortal> portals,
            int edgeId,
            int edgeKey,
            SixthRingPortal.Direction direction,
            SixthRingPortal.BoundaryRole boundaryRole,
            SixthRingPortal.CandidateType candidateType,
            int boundaryNode,
            double fractionFromBase,
            Coordinate crossing,
            String roadName) {
        double normalizedFraction = Math.max(0, Math.min(1, fractionFromBase));
        long fractionKey = Math.round(normalizedFraction * FRACTION_SCALE);
        PortalKey key = new PortalKey(boundaryRole, direction, edgeKey, fractionKey);
        String id = boundaryRole.name().toLowerCase()
                + "-" + direction.name().toLowerCase()
                + "-ek" + edgeKey
                + "-f" + fractionKey;
        SixthRingPortal portal = new SixthRingPortal(
                id,
                edgeId,
                edgeKey,
                direction,
                boundaryRole,
                candidateType,
                boundaryNode,
                normalizedFraction,
                new Wgs84Coordinate(crossing.x, crossing.y),
                roadName);
        portals.merge(key, portal, SixthRingPortalTopologyBuilder::preferPortal);
    }

    private static SixthRingPortal preferPortal(
            SixthRingPortal existing,
            SixthRingPortal candidate) {
        return candidate.candidateType().ordinal() < existing.candidateType().ordinal()
                ? candidate : existing;
    }

    private static double fraction(LineString line, double index) {
        if (line.getLength() <= INDEX_EPSILON) {
            return 0;
        }
        return Math.max(0, Math.min(1, index / line.getLength()));
    }

    private static Coordinate pointAt(LineString line, double index) {
        return new LengthIndexedLine(line).extractPoint(index);
    }

    private static STRtree boundarySegmentIndex(Polygon boundary) {
        STRtree index = new STRtree();
        Coordinate[] coordinates = boundary.getExteriorRing().getCoordinates();
        for (int position = 1; position < coordinates.length; position++) {
            index.insert(new Envelope(coordinates[position - 1], coordinates[position]), position);
        }
        index.build();
        return index;
    }

    private static LineString lineString(PointList points) {
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int index = 0; index < points.size(); index++) {
            coordinates[index] = new Coordinate(points.getLon(index), points.getLat(index));
        }
        return GEOMETRY_FACTORY.createLineString(coordinates);
    }

    private static LineString reverse(LineString line) {
        Coordinate[] source = line.getCoordinates();
        Coordinate[] reversed = new Coordinate[source.length];
        for (int index = 0; index < source.length; index++) {
            reversed[index] = source[source.length - 1 - index].copy();
        }
        return GEOMETRY_FACTORY.createLineString(reversed);
    }

    private static Envelope envelope(PointList points) {
        Envelope envelope = new Envelope();
        for (int index = 0; index < points.size(); index++) {
            envelope.expandToInclude(points.getLon(index), points.getLat(index));
        }
        return envelope;
    }

    private static int distinctCoordinateCount(Coordinate[] coordinates) {
        Set<String> distinct = new HashSet<>();
        for (Coordinate coordinate : coordinates) {
            distinct.add(Math.round(coordinate.x * 100_000_000)
                    + ":" + Math.round(coordinate.y * 100_000_000));
        }
        return distinct.size();
    }

    private enum Side {
        INSIDE,
        BOUNDARY,
        OUTSIDE
    }

    private record SideInterval(
            double startIndex,
            double endIndex,
            Side side) {
    }

    private record DirectedEdgeSide(
            Side firstNonBoundarySide,
            double firstNonBoundaryFraction) {
    }

    private record BoundaryState(
            int node,
            int incomingEdgeId,
            int boundaryHops) {
    }

    private record BoundaryStateKey(
            int node,
            int incomingEdgeId) {
    }

    private record PortalKey(
            SixthRingPortal.BoundaryRole boundaryRole,
            SixthRingPortal.Direction direction,
            int edgeKey,
            long fraction) {
    }
}
