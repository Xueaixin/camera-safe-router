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
import com.graphhopper.storage.TurnCostStorage;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.PointList;
import com.graphhopper.routing.ev.TurnRestriction;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(RoadClassifier.class);

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

        LOGGER.info("道路分类 全边分区开始 边数={}", graph.getEdges());
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
        LOGGER.info("道路分类 全边分区完成 六环主路边={} 通州高速主路边={} 环内禁行高速边={} 匝道边={}",
                sixthRingMainlineEdgeIds.cardinality(),
                tongzhouHighwayMainlineEdges.cardinality(),
                forbiddenSixthInteriorHighwayEdges.cardinality(),
                motorwayLinkEdges.cardinality());

        LOGGER.info("道路分类 连接器分类开始");
        ConnectorClassification connectors = classifyConnectors(
                graph,
                controlledArea,
                linkEdges,
                allHighwayMainlineEdges,
                sixthRingMainlineEdgeIds,
                cameraExemptMainlineEdges);
        LOGGER.info("道路分类 连接器分类完成 释放连接器={} 六环出口key={} 通州连接器={}",
                connectors.releasedConnectorEdges().cardinality(),
                connectors.sixthExitConnectorEdgeKeys().cardinality(),
                connectors.tongzhouHighwayConnectorEdges().cardinality());
        BitSet tongzhouCheckpointBypassEdges = detectTongzhouCheckpointBypasses(
                graph, carAccess, roadClass, roadClassLink,
                tongzhouOutsideSixthEdges, allHighwayMainlineEdges);
        LOGGER.info("道路分类 通州检查站绕行辅路边={}",
                tongzhouCheckpointBypassEdges.cardinality());
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
        LOGGER.info("道路分类 收费站走廊构建完成 入口={} 出口={}",
                tollCorridors.audit().entryCorridors(),
                tollCorridors.audit().exitCorridors());
        LOGGER.info("道路分类 互转走廊构建开始");
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
        LOGGER.info("道路分类 互转走廊构建完成 R->H-T={} H-T->R={}",
                interchangeTopology.audit().rToHtCorridors(),
                interchangeTopology.audit().htToRCorridors());
        List<RoadTurn> illegalDirectAccessTurns = detectIllegalDirectAccessTurns(
                graph, roadClass, roadClassLink, allHighwayMainlineEdges,
                tongzhouCheckpointBypassEdges);
        LOGGER.info("道路分类 非法直连高速转向数={}",
                illegalDirectAccessTurns.size());
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
        append(identity, "tongzhou-checkpoint-bypass", tongzhouCheckpointBypassEdges);
        appendTurns(identity, "illegal-direct-access-turns", illegalDirectAccessTurns);
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
                        tongzhouCheckpointBypassEdges,
                        illegalDirectAccessTurns,
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

    /**
     * 识别通州境内高速检查站绕行辅路：高速主路被 OSM 标为 access=no（检查站导致主路封闭），
     * 两端仍连接可行驶高速，中间由平行 service 辅路衔接。把这些辅路边标记为
     * “通州检查站绕行辅路”，released 寻路阶段允许通行。
     */
    /**
     * 识别“普通道路无匝道直连高速主路”的转向对：非匝道、非高速主路的边与高速主路
     * 通过节点（该节点至少 2 条主路边）相连时，禁止该普通边与任一主路边在该节点上的
     * 直接互转。只禁转向、不禁整边，保留普通道路自身的通行能力。
     */
    private static List<RoadTurn> detectIllegalDirectAccessTurns(
            BaseGraph graph,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            BitSet mainlineEdges,
            BitSet exemptEdges) {
        int[] mainlineIncidence = new int[graph.getNodes()];
        Map<Integer, List<Integer>> mainlineEdgesByNode = new HashMap<>();
        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            if (!mainlineEdges.get(edges.getEdge())) {
                continue;
            }
            int base = edges.getBaseNode();
            int adjacent = edges.getAdjNode();
            mainlineIncidence[base]++;
            mainlineIncidence[adjacent]++;
            mainlineEdgesByNode.computeIfAbsent(base, ignored -> new ArrayList<>())
                    .add(edges.getEdge());
            mainlineEdgesByNode.computeIfAbsent(adjacent, ignored -> new ArrayList<>())
                    .add(edges.getEdge());
        }
        Set<RoadTurn> turns = new HashSet<>();
        edges = graph.getAllEdges();
        while (edges.next()) {
            int edgeId = edges.getEdge();
            if (exemptEdges.get(edgeId)
                    || mainlineEdges.get(edgeId)
                    || edges.get(roadClassLink)) {
                continue;
            }
            for (int node : new int[] {edges.getBaseNode(), edges.getAdjNode()}) {
                if (mainlineIncidence[node] < 2) {
                    continue;
                }
                for (int mainline : mainlineEdgesByNode.getOrDefault(node, List.of())) {
                    if (mainline == edgeId) {
                        continue;
                    }
                    turns.add(new RoadTurn(edgeId, node, mainline));
                    turns.add(new RoadTurn(mainline, node, edgeId));
                }
            }
        }
        List<RoadTurn> sorted = new ArrayList<>(turns);
        sorted.sort(Comparator.comparingInt(RoadTurn::fromEdge)
                .thenComparingInt(RoadTurn::viaNode)
                .thenComparingInt(RoadTurn::toEdge));
        return List.copyOf(sorted);
    }

    /**
     * 把非法直连高速转向写入图内存 TurnCostStorage（运行时写入，不需要重建图缓存）。
     * 两条寻路路径（GraphHopper 标准 A* 与自定义多目标 Dijkstra）都会读取该存储。
     */
    static void applyIllegalDirectAccessTurns(
            BaseGraph graph,
            EncodingManager encodingManager,
            List<RoadTurn> turns) {
        if (turns.isEmpty()) {
            return;
        }
        TurnCostStorage turnCostStorage = graph.getTurnCostStorage();
        if (turnCostStorage == null) {
            LOGGER.warn("道路分类 当前路网未启用 turn cost storage，非法直连高速转向无法生效");
            return;
        }
        BooleanEncodedValue restriction = encodingManager.getTurnBooleanEncodedValue(
                TurnRestriction.key("car"));
        if (restriction == null) {
            throw new IllegalStateException(
                    "car profile turn restriction encoded value missing");
        }
        for (RoadTurn turn : turns) {
            turnCostStorage.set(restriction, turn.fromEdge(), turn.viaNode(), turn.toEdge(), true);
        }
        LOGGER.info("道路分类 非法直连高速转向已写入 turn cost storage 数={}", turns.size());
    }

    private static BitSet detectTongzhouCheckpointBypasses(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            BitSet tongzhouOutsideSixthEdges,
            BitSet allHighwayMainlineEdges) {
        BitSet bypassEdges = new BitSet(graph.getEdges());
        UnionFind components = new UnionFind();
        Map<Integer, List<Integer>> noAccessEdgesByNode = new HashMap<>();
        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            int edgeId = edges.getEdge();
            if (!tongzhouOutsideSixthEdges.get(edgeId)
                    || edges.get(roadClass) != RoadClass.MOTORWAY
                    || edges.get(roadClassLink)
                    || edges.get(carAccess) || edges.getReverse(carAccess)) {
                continue;
            }
            components.union(edges.getBaseNode(), edges.getAdjNode());
            noAccessEdgesByNode.computeIfAbsent(
                    edges.getBaseNode(), ignored -> new ArrayList<>()).add(edgeId);
            noAccessEdgesByNode.computeIfAbsent(
                    edges.getAdjNode(), ignored -> new ArrayList<>()).add(edgeId);
        }
        Map<Integer, Set<Integer>> componentNodes = new HashMap<>();
        Map<Integer, Set<Integer>> componentNoAccessEdges = new HashMap<>();
        for (int node : noAccessEdgesByNode.keySet()) {
            int root = components.find(node);
            componentNodes.computeIfAbsent(root, ignored -> new HashSet<>()).add(node);
            componentNoAccessEdges.computeIfAbsent(root, ignored -> new HashSet<>())
                    .addAll(noAccessEdgesByNode.get(node));
        }
        for (Map.Entry<Integer, Set<Integer>> entry : componentNodes.entrySet()) {
            Set<Integer> noAccess = componentNoAccessEdges.get(entry.getKey());
            List<Integer> endNodes = entry.getValue().stream()
                    .filter(node -> hasDrivableMotorwayNeighbor(
                            graph, carAccess, roadClass, roadClassLink, node, noAccess))
                    .toList();
            if (endNodes.size() != 2) {
                continue;
            }
            Set<Integer> nodesA = reachableNodesWithin(
                    graph, carAccess, roadClass, roadClassLink,
                    allHighwayMainlineEdges, endNodes.get(0), 3_000);
            Set<Integer> nodesB = reachableNodesWithin(
                    graph, carAccess, roadClass, roadClassLink,
                    allHighwayMainlineEdges, endNodes.get(1), 3_000);
            nodesA.retainAll(nodesB);
            if (nodesA.size() >= 2) {
                int marked = 0;
                AllEdgesIterator all = graph.getAllEdges();
                while (all.next()) {
                    int edgeId = all.getEdge();
                    if (allHighwayMainlineEdges.get(edgeId)) {
                        continue;
                    }
                    if (!nodesA.contains(all.getBaseNode())
                            || !nodesA.contains(all.getAdjNode())) {
                        continue;
                    }
                    if (!all.get(carAccess) && !all.getReverse(carAccess)) {
                        continue;
                    }
                    bypassEdges.set(edgeId);
                    marked++;
                }
                LOGGER.info("道路分类 通州检查站绕行辅路 断点边数={} 辅路边数={}",
                        noAccess.size(), marked);
            }
        }
        return bypassEdges;
    }

    private static boolean hasDrivableMotorwayNeighbor(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            int node,
            Set<Integer> excludedEdges) {
        EdgeExplorer explorer = graph.createEdgeExplorer();
        EdgeIterator edge = explorer.setBaseNode(node);
        while (edge.next()) {
            int edgeId = edge.getEdge();
            if (excludedEdges.contains(edgeId)) {
                continue;
            }
            if (edge.get(roadClass) != RoadClass.MOTORWAY || edge.get(roadClassLink)) {
                continue;
            }
            if (edge.get(carAccess) || edge.getReverse(carAccess)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> reachableNodesWithin(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            BitSet allHighwayMainlineEdges,
            int fromNode,
            double maxDistanceMeters) {
        Map<Integer, Double> distances = new HashMap<>();
        distances.put(fromNode, 0.0);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(fromNode);
        EdgeExplorer explorer = graph.createEdgeExplorer();
        while (!queue.isEmpty()) {
            int node = queue.poll();
            EdgeIterator edge = explorer.setBaseNode(node);
            while (edge.next()) {
                int edgeId = edge.getEdge();
                if (allHighwayMainlineEdges.get(edgeId)
                        || (!edge.get(carAccess) && !edge.getReverse(carAccess))) {
                    continue;
                }
                int next = edge.getAdjNode() == node
                        ? edge.getBaseNode() : edge.getAdjNode();
                double candidate = distances.get(node) + edge.getDistance();
                if (candidate > maxDistanceMeters) {
                    continue;
                }
                if (distances.containsKey(next)
                        && distances.get(next) <= candidate) {
                    continue;
                }
                distances.put(next, candidate);
                queue.add(next);
            }
        }
        return distances.keySet();
    }

    private static void append(StringBuilder value, String label, BitSet edges) {
        value.append(label).append('=');
        edges.stream().forEach(edgeId -> value.append(edgeId).append(','));
        value.append('|');
    }

    private static void appendTurns(
            StringBuilder value,
            String label,
            List<RoadTurn> turns) {
        value.append(label).append('=');
        turns.forEach(turn -> value.append(turn.fromEdge())
                .append(':').append(turn.viaNode())
                .append(':').append(turn.toEdge())
                .append(','));
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
