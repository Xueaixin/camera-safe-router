package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class EdgeKeyMultiTargetDijkstra {
    private static final int NO_EDGE = EdgeIterator.NO_EDGE;
    private static final double PORTAL_EDGE_MATCH_TOLERANCE_METERS = 2;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private EdgeKeyMultiTargetDijkstra() {
    }

    static SearchResult search(
            Graph graph,
            Weighting weighting,
            int sourceNode,
            Collection<Portal> portals,
            SearchDirection direction,
            double toleranceMeters,
            int maxVisitedStates,
            Duration timeout) {
        return search(
                graph,
                weighting,
                sourceNode,
                portals,
                direction,
                EdgeTraversalConstraint.ALLOW_ALL,
                toleranceMeters,
                maxVisitedStates,
                timeout);
    }

    static SearchResult search(
            Graph graph,
            Weighting weighting,
            int sourceNode,
            Collection<Portal> portals,
            SearchDirection direction,
            EdgeTraversalConstraint traversalConstraint,
            double toleranceMeters,
            int maxVisitedStates,
            Duration timeout) {
        if (sourceNode < 0 || sourceNode >= graph.getNodes()) {
            throw new IllegalArgumentException("source node is outside graph");
        }
        if (toleranceMeters < 0 || maxVisitedStates <= 0
                || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("invalid search budget");
        }

        Map<Integer, List<Portal>> portalsByOriginalEdgeKey = new HashMap<>();
        for (Portal portal : portals) {
            if (portal.fractionFromBase() < 0 || portal.fractionFromBase() > 1) {
                throw new IllegalArgumentException("portal fraction must be within [0, 1]");
            }
            portalsByOriginalEdgeKey
                    .computeIfAbsent(portal.edgeKey(), ignored -> new ArrayList<>())
                    .add(portal);
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        EdgeExplorer explorer = graph.createEdgeExplorer();
        PriorityQueue<QueueState> queue = new PriorityQueue<>(
                Comparator.comparingDouble(QueueState::distanceMeters)
                        .thenComparingDouble(QueueState::travelTimeSeconds));
        Map<Integer, SettledState> bestStates = new HashMap<>();
        Map<String, PortalPath> bestPortals = new LinkedHashMap<>();
        SearchCursor cursor = new SearchCursor(Double.POSITIVE_INFINITY);

        relaxFromNode(
                weighting, explorer, sourceNode, NO_EDGE, -1, 0, 0,
                portalsByOriginalEdgeKey, direction, traversalConstraint,
                queue, bestStates, bestPortals, cursor);

        int visitedStates = 0;
        Completion completion = Completion.EXHAUSTED;
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                completion = Completion.INTERRUPTED;
                break;
            }
            if (System.nanoTime() >= deadlineNanos) {
                completion = Completion.TIMEOUT;
                break;
            }
            QueueState next = queue.peek();
            if (Double.isFinite(cursor.bestPortalDistanceMeters)
                    && next.distanceMeters()
                    > cursor.bestPortalDistanceMeters + toleranceMeters) {
                completion = Completion.TOLERANCE_SETTLED;
                break;
            }
            queue.poll();
            SettledState best = bestStates.get(next.edgeKey());
            if (best == null
                    || Double.compare(
                    best.travelTimeSeconds(), next.travelTimeSeconds()) != 0
                    || Double.compare(best.distanceMeters(), next.distanceMeters()) != 0) {
                continue;
            }
            if (visitedStates >= maxVisitedStates) {
                completion = Completion.MAX_VISITED_STATES;
                break;
            }
            visitedStates++;
            relaxFromNode(
                    weighting, explorer, next.node(), next.edgeId(), next.edgeKey(),
                    next.travelTimeSeconds(), next.distanceMeters(),
                    portalsByOriginalEdgeKey, direction, traversalConstraint,
                    queue, bestStates, bestPortals, cursor);
        }

        double limit = cursor.bestPortalDistanceMeters + toleranceMeters;
        Map<String, PortalPath> retained = new LinkedHashMap<>();
        bestPortals.values().stream()
                .filter(path -> path.distanceMeters() <= limit)
                .sorted(Comparator.comparingDouble(PortalPath::distanceMeters)
                        .thenComparingDouble(PortalPath::travelTimeSeconds)
                        .thenComparing(path -> path.portal().id()))
                .forEach(path -> retained.put(path.portal().id(), path));
        return new SearchResult(
                Map.copyOf(retained),
                cursor.bestPortalDistanceMeters,
                visitedStates,
                completion,
                completion == Completion.EXHAUSTED && retained.isEmpty());
    }

    private static void relaxFromNode(
            Weighting weighting,
            EdgeExplorer explorer,
            int node,
            int previousOrNextEdgeId,
            int predecessorStateKey,
            double settledTravelTimeSeconds,
            double settledDistanceMeters,
            Map<Integer, List<Portal>> portalsByOriginalEdgeKey,
            SearchDirection direction,
            EdgeTraversalConstraint traversalConstraint,
            PriorityQueue<QueueState> queue,
            Map<Integer, SettledState> bestStates,
            Map<String, PortalPath> bestPortals,
            SearchCursor cursor) {
        EdgeIterator edge = explorer.setBaseNode(node);
        while (edge.next()) {
            boolean reverse = direction == SearchDirection.REVERSE;
            int directedEdgeKey = reverse ? edge.getReverseEdgeKey() : edge.getEdgeKey();
            double edgeWeight = weighting.calcEdgeWeight(edge, reverse);
            if (!Double.isFinite(edgeWeight) || edgeWeight < 0) {
                continue;
            }
            double turnWeight = turnWeight(
                    weighting, previousOrNextEdgeId, node, edge.getEdge(), direction);
            if (!Double.isFinite(turnWeight) || turnWeight < 0) {
                continue;
            }
            double beforeEdge = settledTravelTimeSeconds + turnWeight;
            int originalDirectedEdgeKey = originalDirectedEdgeKey(edge, reverse);
            List<Portal> edgePortals = portalsByOriginalEdgeKey.get(originalDirectedEdgeKey);
            if (edgePortals != null) {
                for (Portal portal : edgePortals) {
                    Projection projection = projection(edge, portal.coordinate());
                    if (projection == null) {
                        continue;
                    }
                    double searchFraction = projection.fractionFromTraversalBase();
                    if (!traversalConstraint.allows(edge, reverse, searchFraction)) {
                        continue;
                    }
                    double directedFraction = reverse ? 1 - searchFraction : searchFraction;
                    double portalTravelTime = beforeEdge + edgeWeight * searchFraction;
                    double portalDistance = settledDistanceMeters
                            + edge.getDistance() * searchFraction;
                    PortalPath existing = bestPortals.get(portal.id());
                    if (existing == null
                            || portalDistance < existing.distanceMeters()
                            || (Double.compare(portalDistance, existing.distanceMeters()) == 0
                            && portalTravelTime < existing.travelTimeSeconds())) {
                        List<Integer> edgeKeys = reconstruct(
                                bestStates,
                                predecessorStateKey,
                                directedEdgeKey,
                                searchFraction,
                                direction);
                        PortalPath path = new PortalPath(
                                portal,
                                portalTravelTime,
                                portalDistance,
                                edgeKeys,
                                directedEdgeKey,
                                directedFraction);
                        bestPortals.put(portal.id(), path);
                        cursor.bestPortalDistanceMeters = Math.min(
                                cursor.bestPortalDistanceMeters, portalDistance);
                    }
                }
            }

            if (!traversalConstraint.allows(edge, reverse)) {
                continue;
            }
            double nextTravelTime = beforeEdge + edgeWeight;
            double nextDistance = settledDistanceMeters + edge.getDistance();
            SettledState existing = bestStates.get(directedEdgeKey);
            if (existing == null
                    || nextDistance < existing.distanceMeters()
                    || (Double.compare(nextDistance, existing.distanceMeters()) == 0
                    && nextTravelTime < existing.travelTimeSeconds())) {
                bestStates.put(directedEdgeKey, new SettledState(
                        directedEdgeKey, nextTravelTime, nextDistance, predecessorStateKey));
                queue.add(new QueueState(
                        directedEdgeKey, edge.getEdge(), edge.getAdjNode(),
                        nextTravelTime, nextDistance));
            }
        }
    }

    private static int originalDirectedEdgeKey(EdgeIteratorState edge, boolean reverse) {
        EdgeIteratorState detached = edge.detach(false);
        int original = detached instanceof VirtualEdgeIteratorState virtualEdge
                ? virtualEdge.getOriginalEdgeKey()
                : edge.getEdgeKey();
        return reverse ? GHUtility.reverseEdgeKey(original) : original;
    }

    private static Projection projection(EdgeIteratorState edge, Wgs84Coordinate portal) {
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        if (points.size() < 2) {
            return null;
        }
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int index = 0; index < points.size(); index++) {
            coordinates[index] = new Coordinate(points.getLon(index), points.getLat(index));
        }
        LengthIndexedLine indexed = new LengthIndexedLine(
                GEOMETRY_FACTORY.createLineString(coordinates));
        Coordinate query = new Coordinate(portal.lng(), portal.lat());
        double index = indexed.project(query);
        Coordinate projected = indexed.extractPoint(index);
        double distance = GeoDistance.meters(
                portal, new Wgs84Coordinate(projected.x, projected.y));
        if (distance > PORTAL_EDGE_MATCH_TOLERANCE_METERS) {
            return null;
        }
        double end = indexed.getEndIndex();
        double fraction = end == 0 ? 0 : index / end;
        return new Projection(Math.max(0, Math.min(1, fraction)));
    }

    private static double turnWeight(
            Weighting weighting,
            int previousOrNextEdgeId,
            int viaNode,
            int candidateEdgeId,
            SearchDirection direction) {
        if (previousOrNextEdgeId == NO_EDGE) {
            return 0;
        }
        return direction == SearchDirection.FORWARD
                ? weighting.calcTurnWeight(previousOrNextEdgeId, viaNode, candidateEdgeId)
                : weighting.calcTurnWeight(candidateEdgeId, viaNode, previousOrNextEdgeId);
    }

    private static List<Integer> reconstruct(
            Map<Integer, SettledState> states,
            int predecessorStateKey,
            int terminalEdgeKey,
            double traversedFraction,
            SearchDirection direction) {
        List<Integer> keys = new ArrayList<>();
        int current = predecessorStateKey;
        while (current >= 0) {
            SettledState state = states.get(current);
            if (state == null) {
                throw new IllegalStateException("missing predecessor state " + current);
            }
            keys.add(state.edgeKey());
            current = state.predecessorStateKey();
        }
        if (direction == SearchDirection.FORWARD) {
            java.util.Collections.reverse(keys);
            if (traversedFraction > 0) {
                keys.add(terminalEdgeKey);
            }
        } else if (traversedFraction > 0) {
            keys.add(0, terminalEdgeKey);
        }
        return List.copyOf(keys);
    }

    enum SearchDirection {
        FORWARD,
        REVERSE
    }

    enum Completion {
        TOLERANCE_SETTLED,
        EXHAUSTED,
        MAX_VISITED_STATES,
        TIMEOUT,
        INTERRUPTED
    }

    record Portal(
            String id,
            int edgeKey,
            double fractionFromBase,
            Wgs84Coordinate coordinate) {
        Portal {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("portal id is required");
            }
            if (coordinate == null) {
                throw new IllegalArgumentException("portal coordinate is required");
            }
        }
    }

    record PortalPath(
            Portal portal,
            double travelTimeSeconds,
            double distanceMeters,
            List<Integer> edgeKeys,
            int terminalEdgeKey,
            double terminalFractionFromBase) {
        PortalPath {
            edgeKeys = List.copyOf(edgeKeys);
        }
    }

    record SearchResult(
            Map<String, PortalPath> candidates,
            double minimumDistanceMeters,
            int visitedStates,
            Completion completion,
            boolean provenNoRoute) {
    }

    private record Projection(double fractionFromTraversalBase) {
    }

    private record QueueState(
            int edgeKey,
            int edgeId,
            int node,
            double travelTimeSeconds,
            double distanceMeters) {
    }

    private record SettledState(
            int edgeKey,
            double travelTimeSeconds,
            double distanceMeters,
            int predecessorStateKey) {
    }

    private static final class SearchCursor {
        private double bestPortalDistanceMeters;

        private SearchCursor(double bestPortalDistanceMeters) {
            this.bestPortalDistanceMeters = bestPortalDistanceMeters;
        }
    }
}
