package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.OSMWayID;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.GHUtility;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

final class TollCorridorTopologyBuilder {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double SOURCE_ENVELOPE_MARGIN_DEGREES = 0.15;
    private static final double GRAPH_NODE_MATCH_METERS = 3;
    private static final double GRID_SIZE_DEGREES = 0.00005;
    private static final double MAX_CORRIDOR_DISTANCE_METERS = 5_000;
    private static final int MAX_SEARCH_STATES = 20_000;

    TollCorridorTopology build(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            IntEncodedValue osmWayId,
            Weighting weighting,
            Geometry controlledArea,
            Geometry sixthRingArea,
            RoadClassificationIndex roadClassification,
            TollBoothSourceData sourceData) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(carAccess, "carAccess");
        Objects.requireNonNull(osmWayId, "osmWayId");
        Objects.requireNonNull(weighting, "weighting");
        Objects.requireNonNull(controlledArea, "controlledArea");
        Objects.requireNonNull(sixthRingArea, "sixthRingArea");
        Objects.requireNonNull(roadClassification, "roadClassification");
        Objects.requireNonNull(sourceData, "sourceData");

        List<TollBoothSourceData.TollBooth> nearbySources = nearbySources(
                sourceData.tollBooths(), sixthRingArea);
        Map<Long, MappedTollBooth> mapped = mapTollBooths(
                graph, nearbySources, osmWayId);
        if (mapped.isEmpty()) {
            return TollCorridorTopology.empty(sourceData.tollBooths().size());
        }

        List<CandidateCorridor> candidates = new ArrayList<>();
        Set<Long> relatedTollBooths = new HashSet<>();
        Set<Long> resolvedTollBooths = new HashSet<>();
        for (MappedTollBooth tollBooth : mapped.values()) {
            List<SearchPath> outsideToToll = search(
                    graph, carAccess, weighting, controlledArea, roadClassification,
                    tollBooth.graphNode(), SearchDirection.REVERSE, SearchTarget.OUTSIDE);
            List<SearchPath> tollToOutside = search(
                    graph, carAccess, weighting, controlledArea, roadClassification,
                    tollBooth.graphNode(), SearchDirection.FORWARD, SearchTarget.OUTSIDE);
            List<SearchPath> tollToMainlines = search(
                    graph, carAccess, weighting, controlledArea, roadClassification,
                    tollBooth.graphNode(), SearchDirection.FORWARD, SearchTarget.SIXTH_RING);
            List<SearchPath> mainlineToTolls = search(
                    graph, carAccess, weighting, controlledArea, roadClassification,
                    tollBooth.graphNode(), SearchDirection.REVERSE, SearchTarget.SIXTH_RING);

            if (!tollToMainlines.isEmpty() || !mainlineToTolls.isEmpty()) {
                relatedTollBooths.add(tollBooth.source().osmNodeId());
            }
            if (!outsideToToll.isEmpty()) {
                for (SearchPath tollToMainline : tollToMainlines) {
                    if (!turnAtTollIsAllowed(
                            weighting,
                            tollBooth.graphNode(),
                            outsideToToll.getFirst(),
                            tollToMainline,
                            true)) {
                        continue;
                    }
                    candidates.add(CandidateCorridor.entry(
                            tollBooth, outsideToToll.getFirst(), tollToMainline));
                    resolvedTollBooths.add(tollBooth.source().osmNodeId());
                }
            }
            if (!tollToOutside.isEmpty()) {
                for (SearchPath mainlineToToll : mainlineToTolls) {
                    if (!turnAtTollIsAllowed(
                            weighting,
                            tollBooth.graphNode(),
                            mainlineToToll,
                            tollToOutside.getFirst(),
                            false)) {
                        continue;
                    }
                    candidates.add(CandidateCorridor.exit(
                            tollBooth, mainlineToToll, tollToOutside.getFirst()));
                    resolvedTollBooths.add(tollBooth.source().osmNodeId());
                }
            }
        }

        assignComplexIds(candidates);
        BitSet entryKeys = new BitSet(graph.getEdges() * 2);
        BitSet exitKeys = new BitSet(graph.getEdges() * 2);
        BitSet baseEdges = new BitSet(graph.getEdges());
        List<TollCorridorTopology.TollCorridor> corridors = candidates.stream()
                .sorted(Comparator
                        .comparingLong((CandidateCorridor value) ->
                                value.tollBooth.source().osmNodeId())
                        .thenComparing(value -> value.role)
                        .thenComparingInt(CandidateCorridor::mainlineEdgeKey))
                .map(candidate -> {
                    BitSet target = candidate.role == TollCorridorTopology.Role.ENTRY
                            ? entryKeys : exitKeys;
                    candidate.directedEdgeKeys.forEach(edgeKey -> {
                        target.set(edgeKey);
                        baseEdges.set(GHUtility.getEdgeFromEdgeKey(edgeKey));
                    });
                    return candidate.toCorridor();
                })
                .toList();
        int entryCount = (int) candidates.stream()
                .filter(value -> value.role == TollCorridorTopology.Role.ENTRY)
                .count();
        int exitCount = candidates.size() - entryCount;
        return new TollCorridorTopology(
                corridors,
                entryKeys,
                exitKeys,
                baseEdges,
                new TollCorridorTopology.Audit(
                        sourceData.tollBooths().size(),
                        mapped.size(),
                        relatedTollBooths.size(),
                        entryCount,
                        exitCount,
                        relatedTollBooths.size() - resolvedTollBooths.size()));
    }

    private static List<TollBoothSourceData.TollBooth> nearbySources(
            List<TollBoothSourceData.TollBooth> source,
            Geometry sixthRingArea) {
        Envelope envelope = new Envelope(sixthRingArea.getEnvelopeInternal());
        envelope.expandBy(SOURCE_ENVELOPE_MARGIN_DEGREES);
        return source.stream()
                .filter(tollBooth -> envelope.covers(
                        tollBooth.coordinate().lng(), tollBooth.coordinate().lat()))
                .toList();
    }

    static Set<Integer> mappedTollGraphNodes(
            BaseGraph graph,
            List<TollBoothSourceData.TollBooth> sources,
            Geometry sixthRingArea,
            IntEncodedValue osmWayId) {
        return mapTollBooths(graph, nearbySources(sources, sixthRingArea), osmWayId)
                .values()
                .stream()
                .map(MappedTollBooth::graphNode)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Map<Long, MappedTollBooth> mapTollBooths(
            BaseGraph graph,
            List<TollBoothSourceData.TollBooth> source,
            IntEncodedValue osmWayId) {
        Map<GridCell, List<TollBoothSourceData.TollBooth>> grid = new HashMap<>();
        for (TollBoothSourceData.TollBooth tollBooth : source) {
            grid.computeIfAbsent(cell(tollBooth.coordinate()), ignored -> new ArrayList<>())
                    .add(tollBooth);
        }
        Map<Long, MappedTollBooth> mapped = new HashMap<>();
        NodeAccess nodes = graph.getNodeAccess();
        EdgeExplorer explorer = graph.createEdgeExplorer();
        for (int graphNode = 0; graphNode < graph.getNodes(); graphNode++) {
            Wgs84Coordinate coordinate = new Wgs84Coordinate(
                    nodes.getLon(graphNode), nodes.getLat(graphNode));
            GridCell center = cell(coordinate);
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                for (int yOffset = -1; yOffset <= 1; yOffset++) {
                    List<TollBoothSourceData.TollBooth> candidates = grid.get(
                            new GridCell(center.x() + xOffset, center.y() + yOffset));
                    if (candidates == null) {
                        continue;
                    }
                    for (TollBoothSourceData.TollBooth candidate : candidates) {
                        double distance = GeoDistance.meters(coordinate, candidate.coordinate());
                        if (distance > GRAPH_NODE_MATCH_METERS) {
                            continue;
                        }
                        int adjacentMatches = adjacentWayMatches(
                                explorer, graphNode, osmWayId, candidate.adjacentWayIds());
                        if (!candidate.adjacentWayIds().isEmpty() && adjacentMatches == 0) {
                            continue;
                        }
                        MappedTollBooth current = mapped.get(candidate.osmNodeId());
                        MappedTollBooth replacement = new MappedTollBooth(
                                candidate, graphNode, distance, adjacentMatches);
                        if (current == null || replacement.isBetterThan(current)) {
                            mapped.put(candidate.osmNodeId(), replacement);
                        }
                    }
                }
            }
        }
        return mapped;
    }

    private static int adjacentWayMatches(
            EdgeExplorer explorer,
            int graphNode,
            IntEncodedValue osmWayId,
            Set<Long> adjacentWayIds) {
        int matches = 0;
        EdgeIterator iterator = explorer.setBaseNode(graphNode);
        while (iterator.next()) {
            if (adjacentWayIds.contains((long) iterator.get(osmWayId))) {
                matches++;
            }
        }
        return matches;
    }

    private static List<SearchPath> search(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            Weighting weighting,
            Geometry controlledArea,
            RoadClassificationIndex roads,
            int startNode,
            SearchDirection direction,
            SearchTarget target) {
        NodeAccess nodes = graph.getNodeAccess();
        if (target == SearchTarget.OUTSIDE && isOutside(controlledArea, nodes, startNode)) {
            return List.of(SearchPath.outside(List.of(), 0));
        }

        SearchState start = new SearchState(startNode, EdgeIterator.NO_EDGE);
        Map<SearchState, Label> labels = new HashMap<>();
        PriorityQueue<QueueEntry> queue = new PriorityQueue<>(
                Comparator.comparingDouble(QueueEntry::weight));
        labels.put(start, new Label(0, 0, null, EdgeIterator.NO_EDGE));
        queue.add(new QueueEntry(start, 0));
        Map<Integer, SearchPath> foundMainlines = new HashMap<>();
        EdgeExplorer explorer = graph.createEdgeExplorer();
        int visited = 0;
        while (!queue.isEmpty() && visited++ < MAX_SEARCH_STATES) {
            QueueEntry currentEntry = queue.poll();
            Label current = labels.get(currentEntry.state());
            if (current == null || current.weight() != currentEntry.weight()) {
                continue;
            }
            if (current.distanceMeters() > MAX_CORRIDOR_DISTANCE_METERS) {
                continue;
            }
            if (target == SearchTarget.OUTSIDE
                    && isOutside(controlledArea, nodes, currentEntry.state().node())) {
                return List.of(reconstruct(currentEntry.state(), labels, direction, -1));
            }

            EdgeIterator edges = explorer.setBaseNode(currentEntry.state().node());
            while (edges.next()) {
                boolean reverse = direction == SearchDirection.REVERSE;
                if (!(reverse ? edges.getReverse(carAccess) : edges.get(carAccess))) {
                    continue;
                }
                int edgeId = edges.getEdge();
                int directedEdgeKey = reverse
                        ? edges.getReverseEdgeKey() : edges.getEdgeKey();
                double edgeWeight = weighting.calcEdgeWeight(edges, reverse);
                if (!Double.isFinite(edgeWeight)) {
                    continue;
                }
                double turnWeight = turnWeight(
                        weighting,
                        direction,
                        currentEntry.state().transitionEdgeId(),
                        currentEntry.state().node(),
                        edgeId);
                if (!Double.isFinite(turnWeight)) {
                    continue;
                }
                if (roads.isSixthRingMainline(edgeId)) {
                    if (target == SearchTarget.SIXTH_RING) {
                        SearchPath path = reconstruct(
                                currentEntry.state(), labels, direction, directedEdgeKey);
                        SearchPath existing = foundMainlines.get(directedEdgeKey);
                        if (existing == null
                                || existing.distanceMeters() > path.distanceMeters()) {
                            foundMainlines.put(directedEdgeKey, path);
                        }
                    }
                    continue;
                }
                if (roads.isAnyHighwayMainline(edgeId)
                        || roads.isForbiddenSixthInteriorHighway(edgeId)) {
                    continue;
                }
                double nextDistance = current.distanceMeters() + edges.getDistance();
                if (nextDistance > MAX_CORRIDOR_DISTANCE_METERS) {
                    continue;
                }
                SearchState next = new SearchState(edges.getAdjNode(), edgeId);
                double nextWeight = current.weight() + turnWeight + edgeWeight;
                Label existing = labels.get(next);
                if (existing != null && existing.weight() <= nextWeight) {
                    continue;
                }
                labels.put(next, new Label(
                        nextWeight, nextDistance, currentEntry.state(), directedEdgeKey));
                queue.add(new QueueEntry(next, nextWeight));
            }
        }
        return foundMainlines.isEmpty()
                ? List.of()
                : foundMainlines.values().stream()
                        .sorted(Comparator.comparingDouble(SearchPath::distanceMeters))
                        .toList();
    }

    private static double turnWeight(
            Weighting weighting,
            SearchDirection direction,
            int transitionEdgeId,
            int viaNode,
            int candidateEdgeId) {
        if (transitionEdgeId == EdgeIterator.NO_EDGE) {
            return 0;
        }
        return direction == SearchDirection.FORWARD
                ? weighting.calcTurnWeight(transitionEdgeId, viaNode, candidateEdgeId)
                : weighting.calcTurnWeight(candidateEdgeId, viaNode, transitionEdgeId);
    }

    private static SearchPath reconstruct(
            SearchState target,
            Map<SearchState, Label> labels,
            SearchDirection direction,
            int mainlineEdgeKey) {
        List<Integer> edgeKeys = new ArrayList<>();
        SearchState current = target;
        Label targetLabel = labels.get(target);
        while (current != null) {
            Label label = labels.get(current);
            if (label == null || label.directedEdgeKey() == EdgeIterator.NO_EDGE) {
                break;
            }
            edgeKeys.add(label.directedEdgeKey());
            current = label.previous();
        }
        if (direction == SearchDirection.FORWARD) {
            java.util.Collections.reverse(edgeKeys);
        }
        return new SearchPath(edgeKeys, mainlineEdgeKey, targetLabel.distanceMeters());
    }

    private static boolean turnAtTollIsAllowed(
            Weighting weighting,
            int tollGraphNode,
            SearchPath first,
            SearchPath second,
            boolean entry) {
        int incomingKey;
        int outgoingKey;
        if (entry) {
            incomingKey = first.directedEdgeKeys().isEmpty()
                    ? EdgeIterator.NO_EDGE : first.directedEdgeKeys().getLast();
            outgoingKey = second.directedEdgeKeys().isEmpty()
                    ? second.mainlineEdgeKey() : second.directedEdgeKeys().getFirst();
        } else {
            incomingKey = first.directedEdgeKeys().isEmpty()
                    ? first.mainlineEdgeKey() : first.directedEdgeKeys().getLast();
            outgoingKey = second.directedEdgeKeys().isEmpty()
                    ? EdgeIterator.NO_EDGE : second.directedEdgeKeys().getFirst();
        }
        if (incomingKey == EdgeIterator.NO_EDGE || outgoingKey == EdgeIterator.NO_EDGE) {
            return true;
        }
        return Double.isFinite(weighting.calcTurnWeight(
                GHUtility.getEdgeFromEdgeKey(incomingKey),
                tollGraphNode,
                GHUtility.getEdgeFromEdgeKey(outgoingKey)));
    }

    private static void assignComplexIds(List<CandidateCorridor> candidates) {
        UnionFind groups = new UnionFind(candidates.size());
        Map<Integer, Integer> firstByBaseEdge = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateCorridor candidate = candidates.get(index);
            for (int edgeKey : candidate.directedEdgeKeys) {
                int edgeId = GHUtility.getEdgeFromEdgeKey(edgeKey);
                Integer existing = firstByBaseEdge.putIfAbsent(edgeId, index);
                if (existing != null) {
                    groups.union(index, existing);
                }
            }
        }
        Map<Integer, Long> minimumNodeByGroup = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            int group = groups.find(index);
            minimumNodeByGroup.merge(
                    group,
                    candidates.get(index).tollBooth.source().osmNodeId(),
                    Math::min);
        }
        for (int index = 0; index < candidates.size(); index++) {
            int group = groups.find(index);
            candidates.get(index).complexId = "sixth-ring-toll-"
                    + minimumNodeByGroup.get(group);
        }
    }

    private static boolean isOutside(
            Geometry controlledArea,
            NodeAccess nodes,
            int graphNode) {
        return !controlledArea.covers(GEOMETRY_FACTORY.createPoint(new Coordinate(
                nodes.getLon(graphNode), nodes.getLat(graphNode))));
    }

    private static GridCell cell(Wgs84Coordinate coordinate) {
        return new GridCell(
                (int) Math.floor(coordinate.lng() / GRID_SIZE_DEGREES),
                (int) Math.floor(coordinate.lat() / GRID_SIZE_DEGREES));
    }

    private enum SearchDirection {
        FORWARD,
        REVERSE
    }

    private enum SearchTarget {
        OUTSIDE,
        SIXTH_RING
    }

    private record GridCell(int x, int y) {
    }

    private record MappedTollBooth(
            TollBoothSourceData.TollBooth source,
            int graphNode,
            double distanceMeters,
            int adjacentWayMatches) {
        private boolean isBetterThan(MappedTollBooth existing) {
            return adjacentWayMatches > existing.adjacentWayMatches
                    || (adjacentWayMatches == existing.adjacentWayMatches
                    && distanceMeters < existing.distanceMeters);
        }
    }

    private record SearchState(int node, int transitionEdgeId) {
    }

    private record Label(
            double weight,
            double distanceMeters,
            SearchState previous,
            int directedEdgeKey) {
    }

    private record QueueEntry(SearchState state, double weight) {
    }

    private record SearchPath(
            List<Integer> directedEdgeKeys,
            int mainlineEdgeKey,
            double distanceMeters) {
        private SearchPath {
            directedEdgeKeys = List.copyOf(directedEdgeKeys);
        }

        private static SearchPath outside(List<Integer> keys, double distanceMeters) {
            return new SearchPath(keys, -1, distanceMeters);
        }
    }

    private static final class CandidateCorridor {
        private final TollCorridorTopology.Role role;
        private final MappedTollBooth tollBooth;
        private final List<Integer> directedEdgeKeys;
        private final int mainlineEdgeKey;
        private final double distanceMeters;
        private String complexId;

        private CandidateCorridor(
                TollCorridorTopology.Role role,
                MappedTollBooth tollBooth,
                List<Integer> directedEdgeKeys,
                int mainlineEdgeKey,
                double distanceMeters) {
            this.role = role;
            this.tollBooth = tollBooth;
            this.directedEdgeKeys = List.copyOf(directedEdgeKeys);
            this.mainlineEdgeKey = mainlineEdgeKey;
            this.distanceMeters = distanceMeters;
        }

        private static CandidateCorridor entry(
                MappedTollBooth tollBooth,
                SearchPath outsideToToll,
                SearchPath tollToMainline) {
            List<Integer> keys = new ArrayList<>(outsideToToll.directedEdgeKeys());
            keys.addAll(tollToMainline.directedEdgeKeys());
            return new CandidateCorridor(
                    TollCorridorTopology.Role.ENTRY,
                    tollBooth,
                    keys,
                    tollToMainline.mainlineEdgeKey(),
                    outsideToToll.distanceMeters() + tollToMainline.distanceMeters());
        }

        private static CandidateCorridor exit(
                MappedTollBooth tollBooth,
                SearchPath mainlineToToll,
                SearchPath tollToOutside) {
            List<Integer> keys = new ArrayList<>(mainlineToToll.directedEdgeKeys());
            keys.addAll(tollToOutside.directedEdgeKeys());
            return new CandidateCorridor(
                    TollCorridorTopology.Role.EXIT,
                    tollBooth,
                    keys,
                    mainlineToToll.mainlineEdgeKey(),
                    mainlineToToll.distanceMeters() + tollToOutside.distanceMeters());
        }

        private TollCorridorTopology.TollCorridor toCorridor() {
            long tollNodeId = tollBooth.source().osmNodeId();
            return new TollCorridorTopology.TollCorridor(
                    complexId + '-' + role.name().toLowerCase() + '-' + tollNodeId
                            + '-' + mainlineEdgeKey,
                    complexId,
                    role,
                    tollNodeId,
                    tollBooth.graphNode(),
                    tollBooth.source().name(),
                    directedEdgeKeys,
                    mainlineEdgeKey,
                    distanceMeters);
        }

        private int mainlineEdgeKey() {
            return mainlineEdgeKey;
        }
    }

    private static final class UnionFind {
        private final int[] parent;

        private UnionFind(int size) {
            parent = new int[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        private int find(int value) {
            if (parent[value] != value) {
                parent[value] = find(parent[value]);
            }
            return parent[value];
        }

        private void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot != secondRoot) {
                parent[secondRoot] = firstRoot;
            }
        }
    }
}
