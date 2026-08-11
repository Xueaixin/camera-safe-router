package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.OSMWayID;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds verified non-toll interchange corridors between the sixth-ring mainline
 * {@code R} and the Tongzhou outside-sixth allowed highway mainline {@code H-T}.
 *
 * <p>A corridor is a complete directed link chain whose two ends touch the two
 * mainlines. Even if part of the chain geometry lies inside the sixth-ring area,
 * the corridor is judged by its source, direction and destination (same principle
 * as toll corridors). Chains containing a mapped toll booth or overlapping toll
 * corridor keys are excluded so tolled interchanges stay on the C-IN/C-OUT path.
 */
final class HighwayInterchangeTopologyBuilder {
    private static final double MAX_CHAIN_DISTANCE_METERS = 3_000;
    private static final int MAX_SEARCH_STATES = 50_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(HighwayInterchangeTopologyBuilder.class);

    HighwayInterchangeTopology build(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            BooleanEncodedValue roadClassLink,
            IntEncodedValue osmWayId,
            Weighting weighting,
            Geometry sixthRingArea,
            RoadClassificationIndex roads,
            TollBoothSourceData tollBoothSourceData,
            TollCorridorTopology tollCorridors) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(carAccess, "carAccess");
        Objects.requireNonNull(roadClassLink, "roadClassLink");
        Objects.requireNonNull(osmWayId, "osmWayId");
        Objects.requireNonNull(weighting, "weighting");
        Objects.requireNonNull(sixthRingArea, "sixthRingArea");
        Objects.requireNonNull(roads, "roads");
        Objects.requireNonNull(tollCorridors, "tollCorridors");

        Map<Integer, List<Integer>> linkEdgesByNode = new HashMap<>();
        Set<Integer> linkEdgeIds = new HashSet<>();
        Map<Integer, List<Integer>> ringArrivals = new HashMap<>();
        Map<Integer, List<Integer>> ringDepartures = new HashMap<>();
        Map<Integer, List<Integer>> htArrivals = new HashMap<>();
        Map<Integer, List<Integer>> htDepartures = new HashMap<>();
        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            boolean link = edges.get(roadClassLink)
                    && (edges.get(carAccess) || edges.getReverse(carAccess));
            if (link) {
                int edgeId = edges.getEdge();
                linkEdgeIds.add(edgeId);
                linkEdgesByNode.computeIfAbsent(edges.getBaseNode(), ignored -> new ArrayList<>())
                        .add(edgeId);
                linkEdgesByNode.computeIfAbsent(edges.getAdjNode(), ignored -> new ArrayList<>())
                        .add(edgeId);
            }
            int edgeId = edges.getEdge();
            boolean ring = roads.isSixthRingMainline(edgeId);
            boolean ht = roads.isTongzhouHighwayMainline(edgeId);
            if (!(ring || ht) || !(edges.get(carAccess) || edges.getReverse(carAccess))) {
                continue;
            }
            Map<Integer, List<Integer>> arrivals = ring ? ringArrivals : htArrivals;
            Map<Integer, List<Integer>> departures = ring ? ringDepartures : htDepartures;
            int baseNode = edges.getBaseNode();
            int adjacentNode = edges.getAdjNode();
            if (edges.get(carAccess)) {
                int key = edges.getEdgeKey();
                departures.computeIfAbsent(baseNode, ignored -> new ArrayList<>()).add(key);
                arrivals.computeIfAbsent(adjacentNode, ignored -> new ArrayList<>()).add(key);
            }
            if (edges.getReverse(carAccess)) {
                int key = edges.getReverseEdgeKey();
                departures.computeIfAbsent(adjacentNode, ignored -> new ArrayList<>()).add(key);
                arrivals.computeIfAbsent(baseNode, ignored -> new ArrayList<>()).add(key);
            }
        }

        Set<Integer> tollGraphNodes = tollBoothSourceData == null
                ? Set.of()
                : TollCorridorTopologyBuilder.mappedTollGraphNodes(
                        graph, tollBoothSourceData.tollBooths(), sixthRingArea, osmWayId);
        BitSet tollKeys = new BitSet(graph.getEdges() * 2);
        tollKeys.or(tollCorridors.entryEdgeKeys());
        tollKeys.or(tollCorridors.exitEdgeKeys());

        NodeUnionFind components = new NodeUnionFind();
        for (int edgeId : linkEdgeIds) {
            EdgeIteratorState link = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            components.union(link.getBaseNode(), link.getAdjNode());
        }
        Map<Integer, Set<Integer>> componentNodes = new HashMap<>();
        Set<Integer> allNodes = new HashSet<>();
        allNodes.addAll(linkEdgesByNode.keySet());
        for (int node : allNodes) {
            componentNodes.computeIfAbsent(components.find(node), ignored -> new LinkedHashSet<>())
                    .add(node);
        }

        List<CandidateChain> candidates = new ArrayList<>();
        Map<CandidateChain, CandidateChain> unique = new HashMap<>();
        int unresolvedComponents = 0;
        int excludedTollNodeChains = 0;
        int excludedTollOverlapChains = 0;
        int componentsTouchingRing = 0;
        int componentsTouchingHT = 0;
        int candidateComponents = 0;
        int[] searchStates = {0};

        LOGGER.info("互转走廊构建开始 link分量={} 收费节点={}",
                componentNodes.size(), tollGraphNodes.size());
        for (Map.Entry<Integer, Set<Integer>> entry : componentNodes.entrySet()) {
            Set<Integer> nodes = entry.getValue();
            boolean touchesRing = nodes.stream()
                    .anyMatch(node -> !ringArrivals.getOrDefault(node, List.of()).isEmpty()
                            || !ringDepartures.getOrDefault(node, List.of()).isEmpty());
            boolean touchesHt = nodes.stream()
                    .anyMatch(node -> !htArrivals.getOrDefault(node, List.of()).isEmpty()
                            || !htDepartures.getOrDefault(node, List.of()).isEmpty());
            if (touchesRing) {
                componentsTouchingRing++;
            }
            if (touchesHt) {
                componentsTouchingHT++;
            }
            if (!touchesRing || !touchesHt) {
                continue;
            }
            candidateComponents++;

            Set<CandidateChain> found = new LinkedHashSet<>();
            for (int startNode : nodes) {
                for (int ringKey : ringArrivals.getOrDefault(startNode, List.of())) {
                    Set<Integer> pathNodes = new LinkedHashSet<>();
                    pathNodes.add(startNode);
                    search(
                            graph, carAccess, weighting, linkEdgesByNode,
                            htDepartures, roads, tollGraphNodes, tollKeys,
                            startNode, ringKey, HighwayInterchangeTopology.Role.R_TO_HT,
                            ringKey,
                            new ArrayList<>(), pathNodes,
                            0, found, searchStates);
                }
                for (int htKey : htArrivals.getOrDefault(startNode, List.of())) {
                    Set<Integer> pathNodes = new LinkedHashSet<>();
                    pathNodes.add(startNode);
                    search(
                            graph, carAccess, weighting, linkEdgesByNode,
                            ringDepartures, roads, tollGraphNodes, tollKeys,
                            startNode, htKey, HighwayInterchangeTopology.Role.HT_TO_R,
                            htKey,
                            new ArrayList<>(), pathNodes,
                            0, found, searchStates);
                }
            }
            for (CandidateChain chain : found) {
                if (!chain.linkKeys().isEmpty() && chain.distanceMeters() <= MAX_CHAIN_DISTANCE_METERS) {
                    unique.put(chain, chain);
                }
            }
            if (found.isEmpty()) {
                unresolvedComponents++;
            }
            int componentRToHt = (int) found.stream()
                    .filter(chain -> chain.role() == HighwayInterchangeTopology.Role.R_TO_HT)
                    .count();
            LOGGER.info("互转走廊 候选分量 #{}, 节点数={}, 生成R->H-T={} H-T->R={}",
                    candidateComponents, nodes.size(),
                    componentRToHt, found.size() - componentRToHt);
        }

        for (CandidateChain chain : unique.keySet()) {
            if (chain.containsTollNode()) {
                excludedTollNodeChains++;
                continue;
            }
            if (chain.overlapsTollKeys()) {
                excludedTollOverlapChains++;
                continue;
            }
            candidates.add(chain);
        }

        assignComplexIds(candidates);
        BitSet rToHtKeys = new BitSet(graph.getEdges() * 2);
        BitSet htToRKeys = new BitSet(graph.getEdges() * 2);
        List<HighwayInterchangeTopology.InterchangeCorridor> corridors = candidates.stream()
                .sorted(Comparator
                        .comparing(CandidateChain::complexId)
                        .thenComparing(chain -> chain.role())
                        .thenComparingInt(CandidateChain::rMainlineEdgeKey)
                        .thenComparingInt(CandidateChain::htMainlineEdgeKey))
                .map(chain -> {
                    BitSet target = chain.role() == HighwayInterchangeTopology.Role.R_TO_HT
                            ? rToHtKeys : htToRKeys;
                    chain.linkKeys().forEach(target::set);
                    return chain.toCorridor();
                })
                .toList();
        int rToHtCount = (int) candidates.stream()
                .filter(chain -> chain.role() == HighwayInterchangeTopology.Role.R_TO_HT)
                .count();
        return new HighwayInterchangeTopology(
                corridors,
                rToHtKeys,
                htToRKeys,
                new HighwayInterchangeTopology.Audit(
                        componentNodes.size(),
                        componentsTouchingRing,
                        componentsTouchingHT,
                        candidateComponents,
                        rToHtCount,
                        candidates.size() - rToHtCount,
                        unresolvedComponents,
                        excludedTollNodeChains,
                        excludedTollOverlapChains));
    }

    private void search(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            Weighting weighting,
            Map<Integer, List<Integer>> linkEdgesByNode,
            Map<Integer, List<Integer>> targetDepartures,
            RoadClassificationIndex roads,
            Set<Integer> tollGraphNodes,
            BitSet tollKeys,
            int node,
            int incomingKey,
            HighwayInterchangeTopology.Role role,
            int startMainlineKey,
            List<Integer> linkKeys,
            Set<Integer> visitedNodes,
            double distanceMeters,
            Set<CandidateChain> found,
            int[] searchStates) {
        if (searchStates[0]++ >= MAX_SEARCH_STATES) {
            return;
        }
        boolean touchesSixthInterior = linkKeys.stream()
                .map(key -> GHUtility.getEdgeFromEdgeKey(key))
                .anyMatch(roads::isSixthInterior);
        Set<Integer> pathNodes = new HashSet<>(visitedNodes);
        pathNodes.add(node);
        if (role == HighwayInterchangeTopology.Role.R_TO_HT) {
            List<Integer> targets = targetDepartures.getOrDefault(node, List.of());
            for (int targetKey : targets) {
                if (turnAllowed(weighting, incomingKey, node, targetKey)) {
                    found.add(new CandidateChain(
                            HighwayInterchangeTopology.Role.R_TO_HT,
                            startMainlineKey,
                            targetKey,
                            new ArrayList<>(linkKeys),
                            distanceMeters,
                            touchesSixthInterior,
                            containsTollNode(pathNodes, tollGraphNodes),
                            overlapsTollKeys(linkKeys, tollKeys)));
                }
            }
        } else {
            List<Integer> targets = targetDepartures.getOrDefault(node, List.of());
            for (int targetKey : targets) {
                if (turnAllowed(weighting, incomingKey, node, targetKey)) {
                    found.add(new CandidateChain(
                            HighwayInterchangeTopology.Role.HT_TO_R,
                            targetKey,
                            startMainlineKey,
                            new ArrayList<>(linkKeys),
                            distanceMeters,
                            touchesSixthInterior,
                            containsTollNode(pathNodes, tollGraphNodes),
                            overlapsTollKeys(linkKeys, tollKeys)));
                }
            }
        }

        for (int linkEdgeId : linkEdgesByNode.getOrDefault(node, List.of())) {
            EdgeIteratorState link = graph.getEdgeIteratorState(linkEdgeId, Integer.MIN_VALUE);
            int nextNode;
            int outgoingKey;
            if (link.getBaseNode() == node && link.get(carAccess)) {
                nextNode = link.getAdjNode();
                outgoingKey = link.getEdgeKey();
            } else if (link.getAdjNode() == node && link.getReverse(carAccess)) {
                nextNode = link.getBaseNode();
                outgoingKey = link.getReverseEdgeKey();
            } else {
                continue;
            }
            if (nextNode == node || visitedNodes.contains(nextNode)) {
                continue;
            }
            if (!turnAllowed(weighting, incomingKey, node, outgoingKey)) {
                continue;
            }
            double nextDistance = distanceMeters + link.getDistance();
            if (nextDistance > MAX_CHAIN_DISTANCE_METERS) {
                continue;
            }
            linkKeys.add(outgoingKey);
            visitedNodes.add(nextNode);
            search(
                    graph, carAccess, weighting, linkEdgesByNode,
                    targetDepartures, roads, tollGraphNodes, tollKeys,
                    nextNode, outgoingKey, role, startMainlineKey, linkKeys, visitedNodes,
                    nextDistance, found, searchStates);
            visitedNodes.remove(nextNode);
            linkKeys.remove(linkKeys.size() - 1);
        }
    }

    private static boolean turnAllowed(
            Weighting weighting,
            int incomingKey,
            int viaNode,
            int outgoingKey) {
        int incomingEdge = GHUtility.getEdgeFromEdgeKey(incomingKey);
        int outgoingEdge = GHUtility.getEdgeFromEdgeKey(outgoingKey);
        return Double.isFinite(weighting.calcTurnWeight(incomingEdge, viaNode, outgoingEdge));
    }

    private static boolean containsTollNode(
            Set<Integer> pathNodes,
            Set<Integer> tollGraphNodes) {
        if (tollGraphNodes.isEmpty()) {
            return false;
        }
        return pathNodes.stream().anyMatch(tollGraphNodes::contains);
    }

    private static boolean overlapsTollKeys(List<Integer> linkKeys, BitSet tollKeys) {
        return linkKeys.stream().anyMatch(tollKeys::get);
    }

    private static void assignComplexIds(List<CandidateChain> candidates) {
        ChainUnionFind groups = new ChainUnionFind(candidates.size());
        Map<Integer, Integer> firstByBaseEdge = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            for (int edgeKey : candidates.get(index).linkKeys()) {
                int edgeId = GHUtility.getEdgeFromEdgeKey(edgeKey);
                Integer existing = firstByBaseEdge.putIfAbsent(edgeId, index);
                if (existing != null) {
                    groups.union(index, existing);
                }
            }
        }
        Map<Integer, Integer> minimumMainlineByGroup = new HashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateChain chain = candidates.get(index);
            int group = groups.find(index);
            minimumMainlineByGroup.merge(
                    group,
                    Math.min(chain.rMainlineEdgeKey(), chain.htMainlineEdgeKey()),
                    Math::min);
        }
        for (int index = 0; index < candidates.size(); index++) {
            int group = groups.find(index);
            candidates.get(index).complexId =
                    "sixth-ring-interchange-" + minimumMainlineByGroup.get(group);
        }
    }

    private static final class CandidateChain {
        private final HighwayInterchangeTopology.Role role;
        private final int rMainlineEdgeKey;
        private final int htMainlineEdgeKey;
        private final List<Integer> linkKeys;
        private final double distanceMeters;
        private final boolean touchesSixthInterior;
        private final boolean containsTollNode;
        private final boolean overlapsTollKeys;
        private String complexId;

        private CandidateChain(
            HighwayInterchangeTopology.Role role,
            int rMainlineEdgeKey,
            int htMainlineEdgeKey,
            List<Integer> linkKeys,
            double distanceMeters,
            boolean touchesSixthInterior,
            boolean containsTollNode,
            boolean overlapsTollKeys) {
            this.role = role;
            this.rMainlineEdgeKey = rMainlineEdgeKey;
            this.htMainlineEdgeKey = htMainlineEdgeKey;
            this.linkKeys = List.copyOf(linkKeys);
            this.distanceMeters = distanceMeters;
            this.touchesSixthInterior = touchesSixthInterior;
            this.containsTollNode = containsTollNode;
            this.overlapsTollKeys = overlapsTollKeys;
        }

        private HighwayInterchangeTopology.Role role() {
            return role;
        }

        private HighwayInterchangeTopology.InterchangeCorridor toCorridor() {
            return new HighwayInterchangeTopology.InterchangeCorridor(
                    complexId + '-' + role.name().toLowerCase() + '-' + rMainlineEdgeKey
                            + '-' + htMainlineEdgeKey,
                    complexId,
                    role,
                    linkKeys,
                    rMainlineEdgeKey,
                    htMainlineEdgeKey,
                    distanceMeters,
                    touchesSixthInterior);
        }

        private List<Integer> linkKeys() {
            return linkKeys;
        }

        private double distanceMeters() {
            return distanceMeters;
        }

        private boolean containsTollNode() {
            return containsTollNode;
        }

        private boolean overlapsTollKeys() {
            return overlapsTollKeys;
        }

        private int rMainlineEdgeKey() {
            return rMainlineEdgeKey;
        }

        private int htMainlineEdgeKey() {
            return htMainlineEdgeKey;
        }

        private String complexId() {
            return complexId;
        }
    }

    private static final class NodeUnionFind {
        private final Map<Integer, Integer> parent = new HashMap<>();

        private int find(int value) {
            int current = parent.computeIfAbsent(value, ignored -> value);
            if (current != value) {
                current = find(current);
                parent.put(value, current);
            }
            return current;
        }

        private void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot != secondRoot) {
                parent.put(secondRoot, firstRoot);
            }
        }
    }

    private static final class ChainUnionFind {
        private final int[] parent;

        private ChainUnionFind(int size) {
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
