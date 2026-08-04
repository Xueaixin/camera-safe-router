package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class EdgeKeyMultiTargetDijkstraPoc {
    private static final int NO_EDGE = EdgeIterator.NO_EDGE;

    private EdgeKeyMultiTargetDijkstraPoc() {
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
        if (sourceNode < 0 || sourceNode >= graph.getNodes()) {
            throw new IllegalArgumentException("source node is outside graph");
        }
        if (toleranceMeters < 0 || maxVisitedStates <= 0 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("invalid search budget");
        }

        Map<Integer, List<Portal>> portalsByEdgeKey = new HashMap<>();
        for (Portal portal : portals) {
            if (portal.fractionFromBase() < 0 || portal.fractionFromBase() > 1) {
                throw new IllegalArgumentException("portal fraction must be within [0, 1]");
            }
            portalsByEdgeKey.computeIfAbsent(portal.edgeKey(), ignored -> new ArrayList<>())
                    .add(portal);
        }

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        EdgeExplorer explorer = graph.createEdgeExplorer();
        PriorityQueue<QueueState> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueState::weight));
        Map<Integer, SettledState> bestStates = new HashMap<>();
        Map<String, PortalPath> bestPortals = new LinkedHashMap<>();
        SearchCursor cursor = new SearchCursor(Double.POSITIVE_INFINITY);

        relaxFromNode(
                weighting, explorer, sourceNode, NO_EDGE, -1, 0,
                portalsByEdgeKey, direction, queue, bestStates, bestPortals, cursor);

        int visitedStates = 0;
        Completion completion = Completion.EXHAUSTED;
        while (!queue.isEmpty()) {
            if (System.nanoTime() >= deadlineNanos) {
                completion = Completion.TIMEOUT;
                break;
            }
            QueueState next = queue.peek();
            if (Double.isFinite(cursor.bestPortalDistance)
                    && next.weight() > cursor.bestPortalDistance + toleranceMeters) {
                completion = Completion.TOLERANCE_SETTLED;
                break;
            }
            queue.poll();
            SettledState best = bestStates.get(next.edgeKey());
            if (best == null || Double.compare(best.weight(), next.weight()) != 0) {
                continue;
            }
            if (visitedStates >= maxVisitedStates) {
                completion = Completion.MAX_VISITED_STATES;
                break;
            }
            visitedStates++;
            relaxFromNode(
                    weighting, explorer, next.node(), next.edgeId(), next.edgeKey(), next.weight(),
                    portalsByEdgeKey, direction, queue, bestStates, bestPortals, cursor);
        }

        double limit = cursor.bestPortalDistance + toleranceMeters;
        Map<String, PortalPath> retained = new LinkedHashMap<>();
        bestPortals.values().stream()
                .filter(path -> path.distanceMeters() <= limit)
                .sorted(Comparator.comparingDouble(PortalPath::distanceMeters)
                        .thenComparing(path -> path.portal().id()))
                .forEach(path -> retained.put(path.portal().id(), path));
        return new SearchResult(
                Map.copyOf(retained),
                cursor.bestPortalDistance,
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
            double settledWeight,
            Map<Integer, List<Portal>> portalsByEdgeKey,
            SearchDirection direction,
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
            double beforeEdge = settledWeight + turnWeight;
            List<Portal> edgePortals = portalsByEdgeKey.get(directedEdgeKey);
            if (edgePortals != null) {
                for (Portal portal : edgePortals) {
                    double fraction = direction == SearchDirection.FORWARD
                            ? portal.fractionFromBase()
                            : 1 - portal.fractionFromBase();
                    double portalDistance = beforeEdge + edgeWeight * fraction;
                    PortalPath existing = bestPortals.get(portal.id());
                    if (existing == null || portalDistance < existing.distanceMeters()) {
                        List<Integer> edgeKeys = reconstruct(
                                bestStates,
                                predecessorStateKey,
                                portal.edgeKey(),
                                fraction,
                                direction);
                        PortalPath path = new PortalPath(portal, portalDistance, edgeKeys);
                        bestPortals.put(portal.id(), path);
                        cursor.bestPortalDistance = Math.min(cursor.bestPortalDistance, portalDistance);
                    }
                }
            }

            double nextWeight = beforeEdge + edgeWeight;
            SettledState existing = bestStates.get(directedEdgeKey);
            if (existing == null || nextWeight < existing.weight()) {
                SettledState updated = new SettledState(
                        directedEdgeKey,
                        nextWeight,
                        predecessorStateKey);
                bestStates.put(directedEdgeKey, updated);
                queue.add(new QueueState(
                        directedEdgeKey, edge.getEdge(), edge.getAdjNode(), nextWeight));
            }
        }
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
            int portalEdgeKey,
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
                keys.add(portalEdgeKey);
            }
        } else {
            if (traversedFraction > 0) {
                keys.add(0, portalEdgeKey);
            }
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
        TIMEOUT
    }

    record Portal(String id, int edgeKey, double fractionFromBase) {
        Portal {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("portal id is required");
            }
        }
    }

    record PortalPath(Portal portal, double distanceMeters, List<Integer> edgeKeys) {
    }

    record SearchResult(
            Map<String, PortalPath> candidates,
            double minimumDistanceMeters,
            int visitedStates,
            Completion completion,
            boolean provenNoRoute) {
    }

    private record QueueState(int edgeKey, int edgeId, int node, double weight) {
    }

    private record SettledState(
            int edgeKey,
            double weight,
            int predecessorStateKey) {
    }

    private static final class SearchCursor {
        private double bestPortalDistance;

        private SearchCursor(double bestPortalDistance) {
            this.bestPortalDistance = bestPortalDistance;
        }
    }
}
