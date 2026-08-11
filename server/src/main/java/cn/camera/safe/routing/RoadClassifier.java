package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.OSMWayID;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadClassLink;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

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

final class RoadClassifier {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private RoadClassifier() {
    }

    static RoadClassification classify(
            BaseGraph graph,
            EncodingManager encodingManager,
            Geometry controlledArea,
            Geometry sixthRingArea,
            Geometry tongzhouArea) {
        return classify(
                graph,
                encodingManager,
                controlledArea,
                sixthRingArea,
                tongzhouArea,
                null,
                null);
    }

    static RoadClassification classify(
            BaseGraph graph,
            EncodingManager encodingManager,
            Geometry controlledArea,
            Geometry sixthRingArea,
            Geometry tongzhouArea,
            Weighting weighting,
            TollBoothSourceData tollBoothSourceData) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(encodingManager, "encodingManager");
        Objects.requireNonNull(controlledArea, "controlledArea");
        Objects.requireNonNull(sixthRingArea, "sixthRingArea");
        Objects.requireNonNull(tongzhouArea, "tongzhouArea");

        BooleanEncodedValue carAccess = encodingManager.getBooleanEncodedValue("car_access");
        EnumEncodedValue<RoadClass> roadClass = encodingManager.getEnumEncodedValue(
                RoadClass.KEY, RoadClass.class);
        BooleanEncodedValue roadClassLink = encodingManager.getBooleanEncodedValue(
                RoadClassLink.KEY);
        BooleanEncodedValue sixthRingIdentity = encodingManager.getBooleanEncodedValue(
                RoadIdentityEncodedValues.SIXTH_RING_MAINLINE);
        IntEncodedValue osmWayId = encodingManager.getIntEncodedValue(OSMWayID.KEY);

        Set<Integer> sixthRingWays = new HashSet<>();
        Set<Integer> tongzhouMotorwayWays = new HashSet<>();
        int sixthRingRelationEdges = 0;
        int sixthRingMainlineEdges = 0;
        int sixthRingDrivableDirections = 0;
        int sixthRingClassificationMismatches = 0;
        int tongzhouMotorwayMainlineEdges = 0;
        int tongzhouMotorwayDrivableDirections = 0;
        int tongzhouMotorwayLinkEdges = 0;

        BitSet cameraExemptMainlineEdges = new BitSet(graph.getEdges());
        BitSet allHighwayMainlineEdges = new BitSet(graph.getEdges());
        BitSet sixthRingMainlineEdgeIds = new BitSet(graph.getEdges());
        BitSet tongzhouHighwayMainlineEdges = new BitSet(graph.getEdges());
        BitSet forbiddenSixthInteriorHighwayEdges = new BitSet(graph.getEdges());
        BitSet motorwayLinkEdges = new BitSet(graph.getEdges());
        BitSet sixthInteriorEdges = new BitSet(graph.getEdges());
        BitSet tongzhouOutsideSixthEdges = new BitSet(graph.getEdges());
        List<LinkEdge> linkEdges = new ArrayList<>();
        Geometry sixthRingInterior = sixthRingArea.buffer(-1e-9);
        if (sixthRingInterior.isEmpty()) {
            sixthRingInterior = sixthRingArea;
        }

        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            PointList points = edges.fetchWayGeometry(FetchMode.ALL);
            LineString geometry = lineString(points);
            boolean inSixthRing = sixthRingInterior.intersects(geometry);
            boolean inTongzhouOutsideSixth = !inSixthRing
                    && tongzhouArea.covers(geometry);
            if (inSixthRing) {
                sixthInteriorEdges.set(edges.getEdge());
            } else if (inTongzhouOutsideSixth) {
                tongzhouOutsideSixthEdges.set(edges.getEdge());
            }

            RoadClass edgeRoadClass = edges.get(roadClass);
            boolean link = edges.get(roadClassLink);
            boolean forward = edges.get(carAccess);
            boolean reverse = edges.getReverse(carAccess);
            int wayId = edges.get(osmWayId);

            if (link && edgeRoadClass == RoadClass.MOTORWAY) {
                motorwayLinkEdges.set(edges.getEdge());
                linkEdges.add(new LinkEdge(
                        edges.getEdge(), edges.getEdgeKey(), edges.getReverseEdgeKey(),
                        edges.getBaseNode(), edges.getAdjNode(), edges.getDistance(),
                        geometry, inSixthRing, inTongzhouOutsideSixth));
                if (inTongzhouOutsideSixth) {
                    tongzhouMotorwayLinkEdges++;
                }
            }

            if (edges.get(sixthRingIdentity)) {
                sixthRingRelationEdges++;
                if (edgeRoadClass == RoadClass.MOTORWAY && !link) {
                    allHighwayMainlineEdges.set(edges.getEdge());
                    cameraExemptMainlineEdges.set(edges.getEdge());
                    sixthRingMainlineEdgeIds.set(edges.getEdge());
                    sixthRingMainlineEdges++;
                    sixthRingWays.add(wayId);
                    sixthRingDrivableDirections += (forward ? 1 : 0) + (reverse ? 1 : 0);
                } else {
                    sixthRingClassificationMismatches++;
                }
                continue;
            }

            if (edgeRoadClass != RoadClass.MOTORWAY || link) {
                continue;
            }
            allHighwayMainlineEdges.set(edges.getEdge());
            if (inSixthRing) {
                forbiddenSixthInteriorHighwayEdges.set(edges.getEdge());
            } else if (inTongzhouOutsideSixth) {
                cameraExemptMainlineEdges.set(edges.getEdge());
                tongzhouHighwayMainlineEdges.set(edges.getEdge());
                tongzhouMotorwayMainlineEdges++;
                tongzhouMotorwayWays.add(wayId);
                tongzhouMotorwayDrivableDirections += (forward ? 1 : 0) + (reverse ? 1 : 0);
            }
        }

        ConnectorClassification connectors = classifyConnectors(
                graph,
                controlledArea,
                linkEdges,
                allHighwayMainlineEdges,
                sixthRingMainlineEdgeIds,
                cameraExemptMainlineEdges);
        RoadClassificationAudit audit = new RoadClassificationAudit(
                sixthRingRelationEdges,
                sixthRingMainlineEdges,
                sixthRingWays.size(),
                sixthRingDrivableDirections,
                sixthRingClassificationMismatches,
                tongzhouMotorwayMainlineEdges,
                tongzhouMotorwayWays.size(),
                tongzhouMotorwayDrivableDirections,
                tongzhouMotorwayLinkEdges);

        RoadClassificationIndex baseClassification = new RoadClassificationIndex(
                cameraExemptMainlineEdges,
                allHighwayMainlineEdges,
                sixthRingMainlineEdgeIds,
                tongzhouHighwayMainlineEdges,
                forbiddenSixthInteriorHighwayEdges,
                motorwayLinkEdges,
                sixthInteriorEdges,
                tongzhouOutsideSixthEdges,
                connectors.releasedConnectorEdges(),
                connectors.sixthExitConnectorEdgeKeys(),
                connectors.tongzhouHighwayConnectorEdges(),
                "pending-toll-corridor-classification");
        TollCorridorTopology tollCorridors = weighting == null || tollBoothSourceData == null
                ? TollCorridorTopology.empty(
                        tollBoothSourceData == null ? 0 : tollBoothSourceData.tollBooths().size())
                : new TollCorridorTopologyBuilder().build(
                        graph,
                        carAccess,
                        osmWayId,
                        weighting,
                        controlledArea,
                        sixthRingArea,
                        baseClassification,
                        tollBoothSourceData);
        HighwayInterchangeTopology interchangeTopology =
                weighting == null || tollBoothSourceData == null
                        ? HighwayInterchangeTopology.empty()
                        : new HighwayInterchangeTopologyBuilder().build(
                                graph,
                                carAccess,
                                roadClassLink,
                                osmWayId,
                                weighting,
                                sixthRingArea,
                                baseClassification,
                                tollBoothSourceData,
                                tollCorridors);
        BitSet releasedConnectorEdges = (BitSet) connectors.releasedConnectorEdges().clone();
        releasedConnectorEdges.or(tollCorridors.baseEdges());
        BitSet sixthExitConnectorEdgeKeys = tollCorridors.corridors().isEmpty()
                ? connectors.sixthExitConnectorEdgeKeys()
                : tollCorridors.exitEdgeKeys();

        StringBuilder identity = new StringBuilder("road-zone-v4|");
        append(identity, "exempt", cameraExemptMainlineEdges);
        append(identity, "all-mainline", allHighwayMainlineEdges);
        append(identity, "sixth", sixthRingMainlineEdgeIds);
        append(identity, "tongzhou-highway", tongzhouHighwayMainlineEdges);
        append(identity, "forbidden-sixth-highway", forbiddenSixthInteriorHighwayEdges);
        append(identity, "sixth-exit-keys", sixthExitConnectorEdgeKeys);
        append(identity, "sixth-toll-entry-keys", tollCorridors.entryEdgeKeys());
        append(identity, "sixth-toll-exit-keys", tollCorridors.exitEdgeKeys());
        append(identity, "tongzhou-connectors", connectors.tongzhouHighwayConnectorEdges());
        append(identity, "rht-entry-keys", interchangeTopology.htToREdgeKeys());
        append(identity, "rht-exit-keys", interchangeTopology.rToHtEdgeKeys());
        return new RoadClassification(
                new RoadClassificationIndex(
                        cameraExemptMainlineEdges,
                        allHighwayMainlineEdges,
                        sixthRingMainlineEdgeIds,
                        tongzhouHighwayMainlineEdges,
                        forbiddenSixthInteriorHighwayEdges,
                        motorwayLinkEdges,
                        sixthInteriorEdges,
                        tongzhouOutsideSixthEdges,
                        releasedConnectorEdges,
                        sixthExitConnectorEdgeKeys,
                        tollCorridors.entryEdgeKeys(),
                        tollCorridors.exitEdgeKeys(),
                        connectors.tongzhouHighwayConnectorEdges(),
                        interchangeTopology.htToREdgeKeys(),
                        interchangeTopology.rToHtEdgeKeys(),
                        "sha256:" + Hashing.sha256(identity.toString())),
                audit,
                tollCorridors,
                interchangeTopology);
    }

    static RoadClassificationAudit audit(
            BaseGraph graph,
            EncodingManager encodingManager,
            Geometry tongzhouArea) {
        return classify(
                graph, encodingManager, tongzhouArea,
                GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                        new Coordinate(-180, -90), new Coordinate(-179, -90),
                        new Coordinate(-179, -89), new Coordinate(-180, -89),
                        new Coordinate(-180, -90)
                }),
                tongzhouArea).audit();
    }

    private static ConnectorClassification classifyConnectors(
            BaseGraph graph,
            Geometry controlledArea,
            List<LinkEdge> links,
            BitSet allMainlines,
            BitSet sixthRingMainlines,
            BitSet allowedControlledMainlines) {
        UnionFind components = new UnionFind();
        Map<Integer, List<LinkEdge>> linksByNode = new HashMap<>();
        for (LinkEdge link : links) {
            components.union(link.baseNode(), link.adjacentNode());
            linksByNode.computeIfAbsent(link.baseNode(), ignored -> new ArrayList<>()).add(link);
            linksByNode.computeIfAbsent(link.adjacentNode(), ignored -> new ArrayList<>()).add(link);
        }

        Map<Integer, Set<Integer>> allMainlineTouches = mainlineTouches(
                graph, components, allMainlines);
        Map<Integer, Set<Integer>> sixthRingTouches = mainlineTouches(
                graph, components, sixthRingMainlines);
        Map<Integer, Set<Integer>> allowedMainlineTouches = mainlineTouches(
                graph, components, allowedControlledMainlines);
        Map<Integer, Set<Integer>> outsideSeeds = new HashMap<>();
        NodeAccess nodes = graph.getNodeAccess();
        for (LinkEdge link : links) {
            int component = components.find(link.baseNode());
            boolean baseOutside = isOutside(controlledArea, nodes, link.baseNode());
            boolean adjacentOutside = isOutside(
                    controlledArea, nodes, link.adjacentNode());
            if (baseOutside) {
                outsideSeeds.computeIfAbsent(component, ignored -> new HashSet<>())
                        .add(link.baseNode());
            }
            if (adjacentOutside) {
                outsideSeeds.computeIfAbsent(component, ignored -> new HashSet<>())
                        .add(link.adjacentNode());
            }
            if (!baseOutside && !adjacentOutside && !controlledArea.covers(link.geometry())) {
                outsideSeeds.computeIfAbsent(component, ignored -> new HashSet<>())
                        .add(link.baseNode());
                outsideSeeds.get(component).add(link.adjacentNode());
            }
        }

        BitSet released = new BitSet(graph.getEdges());
        BitSet sixthExitKeys = new BitSet(graph.getEdges() * 2);
        BitSet tongzhouHighwayConnectors = new BitSet(graph.getEdges());
        Map<Integer, List<LinkEdge>> linksByComponent = new HashMap<>();
        for (LinkEdge link : links) {
            int component = components.find(link.baseNode());
            linksByComponent.computeIfAbsent(component, ignored -> new ArrayList<>()).add(link);
        }
        for (Map.Entry<Integer, List<LinkEdge>> entry : linksByComponent.entrySet()) {
            int component = entry.getKey();
            List<LinkEdge> componentLinks = entry.getValue();
            boolean reachesOutside = !outsideSeeds.getOrDefault(component, Set.of()).isEmpty();
            int allTouches = allMainlineTouches.getOrDefault(component, Set.of()).size();
            if (allTouches >= 2 || (allTouches >= 1 && reachesOutside)) {
                componentLinks.forEach(link -> released.set(link.edgeId()));
            }
            if (allowedMainlineTouches.getOrDefault(component, Set.of()).size() >= 2) {
                componentLinks.stream()
                        .filter(LinkEdge::inTongzhouOutsideSixth)
                        .forEach(link -> tongzhouHighwayConnectors.set(link.edgeId()));
            }
            if (!reachesOutside
                    || sixthRingTouches.getOrDefault(component, Set.of()).isEmpty()) {
                continue;
            }
            Map<Integer, Double> distanceToOutside = connectorDistanceToOutside(
                    componentLinks, outsideSeeds.get(component), linksByNode);
            for (LinkEdge link : componentLinks) {
                if (!link.inSixthRing()) {
                    continue;
                }
                double baseDistance = distanceToOutside.getOrDefault(
                        link.baseNode(), Double.POSITIVE_INFINITY);
                double adjacentDistance = distanceToOutside.getOrDefault(
                        link.adjacentNode(), Double.POSITIVE_INFINITY);
                if (adjacentDistance < baseDistance) {
                    sixthExitKeys.set(link.edgeKey());
                }
                if (baseDistance < adjacentDistance) {
                    sixthExitKeys.set(link.reverseEdgeKey());
                }
            }
        }
        return new ConnectorClassification(released, sixthExitKeys, tongzhouHighwayConnectors);
    }

    private static Map<Integer, Set<Integer>> mainlineTouches(
            BaseGraph graph,
            UnionFind components,
            BitSet mainlines) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        mainlines.stream().forEach(edgeId -> {
            var edge = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            addMainlineTouch(components, result, edge.getBaseNode(), edgeId);
            addMainlineTouch(components, result, edge.getAdjNode(), edgeId);
        });
        return result;
    }

    private static void addMainlineTouch(
            UnionFind components,
            Map<Integer, Set<Integer>> touches,
            int node,
            int edgeId) {
        if (components.contains(node)) {
            touches.computeIfAbsent(components.find(node), ignored -> new HashSet<>()).add(edgeId);
        }
    }

    private static boolean isOutside(
            Geometry controlledArea,
            NodeAccess nodes,
            int node) {
        return !controlledArea.covers(GEOMETRY_FACTORY.createPoint(
                new Coordinate(nodes.getLon(node), nodes.getLat(node))));
    }

    private static Map<Integer, Double> connectorDistanceToOutside(
            List<LinkEdge> componentLinks,
            Set<Integer> outsideSeeds,
            Map<Integer, List<LinkEdge>> linksByNode) {
        Set<Integer> componentEdgeIds = new HashSet<>();
        componentLinks.forEach(link -> componentEdgeIds.add(link.edgeId()));
        Map<Integer, Double> distances = new HashMap<>();
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(
                Comparator.comparingDouble(NodeDistance::distance));
        for (int seed : outsideSeeds) {
            distances.put(seed, 0.0);
            queue.add(new NodeDistance(seed, 0));
        }
        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();
            if (current.distance() != distances.getOrDefault(
                    current.node(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            for (LinkEdge link : linksByNode.getOrDefault(current.node(), List.of())) {
                if (!componentEdgeIds.contains(link.edgeId())) {
                    continue;
                }
                int next = link.baseNode() == current.node()
                        ? link.adjacentNode() : link.baseNode();
                double candidate = current.distance() + Math.max(0.001, link.distanceMeters());
                if (candidate < distances.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    distances.put(next, candidate);
                    queue.add(new NodeDistance(next, candidate));
                }
            }
        }
        return distances;
    }

    private static LineString lineString(PointList points) {
        if (points.size() < 2) {
            throw new IllegalStateException("road edge geometry requires at least two points");
        }
        Coordinate[] coordinates = new Coordinate[points.size()];
        for (int index = 0; index < points.size(); index++) {
            coordinates[index] = new Coordinate(points.getLon(index), points.getLat(index));
        }
        return GEOMETRY_FACTORY.createLineString(coordinates);
    }

    private static void append(StringBuilder value, String label, BitSet edges) {
        value.append(label).append('=');
        edges.stream().forEach(edgeId -> value.append(edgeId).append(','));
        value.append('|');
    }

    private record LinkEdge(
            int edgeId,
            int edgeKey,
            int reverseEdgeKey,
            int baseNode,
            int adjacentNode,
            double distanceMeters,
            LineString geometry,
            boolean inSixthRing,
            boolean inTongzhouOutsideSixth) {
    }

    private record ConnectorClassification(
            BitSet releasedConnectorEdges,
            BitSet sixthExitConnectorEdgeKeys,
            BitSet tongzhouHighwayConnectorEdges) {
    }

    private record NodeDistance(int node, double distance) {
    }

    private static final class UnionFind {
        private final Map<Integer, Integer> parent = new HashMap<>();

        boolean contains(int node) {
            return parent.containsKey(node);
        }

        int find(int node) {
            int current = parent.computeIfAbsent(node, ignored -> node);
            if (current != node) {
                current = find(current);
                parent.put(node, current);
            }
            return current;
        }

        void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot != secondRoot) {
                parent.put(secondRoot, firstRoot);
            }
        }
    }
}
