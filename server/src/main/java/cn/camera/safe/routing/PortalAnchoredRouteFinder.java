package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.AlgorithmOptions;
import com.graphhopper.routing.EdgeToEdgeRoutingAlgorithm;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.RoutingAlgorithm;
import com.graphhopper.routing.RoutingAlgorithmFactorySimple;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadClassLink;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;

import static com.graphhopper.util.EdgeIterator.ANY_EDGE;
import static com.graphhopper.util.Parameters.Algorithms.ASTAR_BI;

/** Routes the outer reference leg from the exact directed edge selected as the boundary portal. */
final class PortalAnchoredRouteFinder {
    private static final double FRACTION_EPSILON = 1e-9;

    private PortalAnchoredRouteFinder() {
    }

    static PortalAnchoredRoute route(
            HardAvoidingGraphHopper hopper,
            ControlReleasePoint portal,
            Wgs84Coordinate externalEndpoint,
            RoutingSnapshot snapshot,
            EdgeTraversalConstraint traversalConstraint,
            Weighting baseWeighting,
            int maxVisitedNodes,
            Duration timeout) {
        BaseGraph baseGraph = hopper.getBaseGraph();
        BooleanEncodedValue carAccess = hopper.getEncodingManager()
                .getBooleanEncodedValue("car_access");
        EnumEncodedValue<RoadClass> roadClass = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        BooleanEncodedValue roadClassLink = hopper.getEncodingManager()
                .getBooleanEncodedValue(RoadClassLink.KEY);
        EnumEncodedValue<RoadEnvironment> roadEnvironment = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class);
        EdgeFilter routable = edge -> edge.get(carAccess) || edge.getReverse(carAccess);
        Snap portalSnap = hopper.getLocationIndex().findClosest(
                portal.coordinate().lat(),
                portal.coordinate().lng(),
                edge -> edge.getEdge() == portal.edgeId() && routable.accept(edge));
        Snap endpointSnap = hopper.getLocationIndex().findClosest(
                externalEndpoint.lat(), externalEndpoint.lng(), routable);
        if (!portalSnap.isValid() || portalSnap.getClosestEdge().getEdge() != portal.edgeId()
                || !endpointSnap.isValid()) {
            throw new RoutingEngineException(
                    RoutingEngineException.Reason.POINT_NOT_FOUND,
                    "边界通行边或环外端点无法吸附到当前路网");
        }

        boolean outbound = portal.direction() == SixthRingPortal.Direction.OUTBOUND;
        Snap fromSnap = outbound ? portalSnap : endpointSnap;
        Snap toSnap = outbound ? endpointSnap : portalSnap;
        QueryGraph queryGraph = QueryGraph.create(baseGraph, fromSnap, toSnap);
        boolean portalRemainderExists = outbound
                ? portal.fractionFromBase() < 1 - FRACTION_EPSILON
                : portal.fractionFromBase() > FRACTION_EPSILON;
        int forcedPortalEdge = portalRemainderExists
                ? forcedPortalEdge(queryGraph, portalSnap, portal, outbound)
                : ANY_EDGE;

        SearchAudit audit = new SearchAudit();
        Weighting weighting = new BlockedEdgeWeighting(
                new TraversalConstrainedWeighting(
                        queryGraph.wrapWeighting(baseWeighting), traversalConstraint),
                snapshot.blockedEdges(),
                audit,
                baseGraph.getEdges());
        AlgorithmOptions options = new AlgorithmOptions()
                .setAlgorithm(ASTAR_BI)
                .setTraversalMode(TraversalMode.EDGE_BASED)
                .setMaxVisitedNodes(maxVisitedNodes)
                .setTimeoutMillis(timeout.toMillis());
        RoutingAlgorithm algorithm = new RoutingAlgorithmFactorySimple()
                .createAlgo(queryGraph, weighting, options);
        if (!(algorithm instanceof EdgeToEdgeRoutingAlgorithm edgeToEdge)) {
            throw new IllegalStateException("portal-anchored routing requires an edge-to-edge algorithm");
        }

        Path path = outbound
                ? edgeToEdge.calcPath(
                        fromSnap.getClosestNode(), toSnap.getClosestNode(), forcedPortalEdge, ANY_EDGE)
                : edgeToEdge.calcPath(
                        fromSnap.getClosestNode(), toSnap.getClosestNode(), ANY_EDGE, forcedPortalEdge);
        if (!path.isFound()) {
            RoutingEngineException.Reason reason = algorithm.getVisitedNodes() >= maxVisitedNodes
                    ? RoutingEngineException.Reason.RESOURCE_LIMIT
                    : RoutingEngineException.Reason.NO_ROUTE;
            throw new RoutingEngineException(reason, "边界通行边无法连续连接环外参考路线");
        }

        List<EdgeIteratorState> edges = path.calcEdges();
        if (edges.isEmpty()) {
            throw new RoutingEngineException(
                    RoutingEngineException.Reason.NO_ROUTE,
                    "边界通行边参考路线为空");
        }
        validatePortalConnection(
                edges, portal, outbound, portalRemainderExists,
                baseGraph, baseWeighting);
        rejectBlockedEdges(edges, snapshot.blockedEdges(), baseGraph.getEdges());

        GeometryTrace geometryTrace = geometryTrace(
                edges, roadClass, roadClassLink, roadEnvironment, baseGraph.getEdges());
        List<Wgs84Coordinate> geometry = geometryTrace.geometry();
        if (geometry.size() < 2) {
            throw new RoutingEngineException(
                    RoutingEngineException.Reason.NO_ROUTE,
                    "边界通行边参考路线几何为空");
        }
        return new PortalAnchoredRoute(
                new EngineRoute(
                        path.getDistance(),
                        path.getTime(),
                        geometry,
                        geometryTrace.trace(),
                        audit.edgeChecks(),
                        audit.virtualEdgeChecks(),
                        audit.blockedRejections()),
                geometryTrace.trace());
    }

    private static GeometryTrace geometryTrace(
            List<EdgeIteratorState> edges,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            EnumEncodedValue<RoadEnvironment> roadEnvironment,
            int baseEdgeCount) {
        List<Wgs84Coordinate> geometry = new ArrayList<>();
        List<RouteTracePoint> trace = new ArrayList<>();
        for (EdgeIteratorState edge : edges) {
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            for (int pointIndex = 0; pointIndex < points.size(); pointIndex++) {
                Wgs84Coordinate coordinate = new Wgs84Coordinate(
                        points.getLon(pointIndex), points.getLat(pointIndex));
                int geometryIndex;
                if (!geometry.isEmpty() && geometry.getLast().equals(coordinate)) {
                    geometryIndex = geometry.size() - 1;
                } else {
                    geometry.add(coordinate);
                    geometryIndex = geometry.size() - 1;
                }
                trace.add(new RouteTracePoint(
                        geometryIndex,
                        coordinate,
                        edge.getName(),
                        edge.get(roadClass),
                        edge.get(roadClassLink),
                        edge.get(roadEnvironment),
                        OriginalEdgeKey.resolve(edge, baseEdgeCount)));
            }
        }
        return new GeometryTrace(List.copyOf(geometry), List.copyOf(trace));
    }

    private static void validatePortalConnection(
            List<EdgeIteratorState> edges,
            ControlReleasePoint portal,
            boolean outbound,
            boolean portalRemainderExists,
            BaseGraph baseGraph,
            Weighting baseWeighting) {
        EdgeIteratorState pathEdge = outbound ? edges.getFirst() : edges.getLast();
        if (portalRemainderExists) {
            if (originalEdgeKey(pathEdge, baseGraph.getEdges()) != portal.edgeKey()) {
                throw new IllegalStateException(
                        "reference route did not preserve the selected portal direction");
            }
            return;
        }

        EdgeIteratorState portalEdge = baseGraph.getEdgeIteratorStateForKey(portal.edgeKey());
        int crossingNode = outbound ? portalEdge.getAdjNode() : portalEdge.getBaseNode();
        if ((outbound ? pathEdge.getBaseNode() : pathEdge.getAdjNode()) != crossingNode) {
            throw new IllegalStateException("reference route is not connected to the portal boundary node");
        }
        double turnWeight = outbound
                ? baseWeighting.calcTurnWeight(
                        portalEdge.getEdge(), crossingNode, pathEdge.getEdge())
                : baseWeighting.calcTurnWeight(
                        pathEdge.getEdge(), crossingNode, portalEdge.getEdge());
        if (!Double.isFinite(turnWeight) || turnWeight < 0) {
            throw new IllegalStateException("reference route makes an illegal turn at the portal boundary node");
        }
    }

    private static int forcedPortalEdge(
            QueryGraph queryGraph,
            Snap portalSnap,
            ControlReleasePoint portal,
            boolean outbound) {
        int expectedOutgoingKey = outbound
                ? portal.edgeKey()
                : GHUtility.reverseEdgeKey(portal.edgeKey());
        StringBuilder observed = new StringBuilder();
        EdgeIterator edge = queryGraph.createEdgeExplorer().setBaseNode(portalSnap.getClosestNode());
        while (edge.next()) {
            EdgeIteratorState state = queryGraph.getEdgeIteratorStateForKey(edge.getEdgeKey());
            int originalKey = originalEdgeKey(state, queryGraph.getBaseGraph().getEdges());
            if (!observed.isEmpty()) {
                observed.append(',');
            }
            observed.append(edge.getEdge()).append(':').append(originalKey);
            if (originalKey == expectedOutgoingKey) {
                return edge.getEdge();
            }
        }
        throw new IllegalStateException("selected portal direction is not connected to its forced snap: "
                + portal.id() + " edgeKey=" + portal.edgeKey()
                + " fraction=" + portal.fractionFromBase()
                + " outbound=" + outbound
                + " snap=" + portalSnap
                + " expected=" + expectedOutgoingKey
                + " observed=" + observed);
    }

    private static void rejectBlockedEdges(
            List<EdgeIteratorState> edges,
            BlockedEdgeSnapshot blockedEdges,
            int baseEdgeCount) {
        boolean violation = edges.stream()
                .mapToInt(edge -> originalEdgeKey(edge, baseEdgeCount))
                .anyMatch(blockedEdges::isBlockedEdgeKey);
        if (violation) {
            throw new IllegalStateException("portal-anchored route contains a blocked edge");
        }
    }

    private static int originalEdgeKey(EdgeIteratorState edge, int baseEdgeCount) {
        if (edge.getEdge() < baseEdgeCount) {
            return edge.getEdgeKey();
        }
        EdgeIteratorState detached = edge.detach(false);
        if (!(detached instanceof VirtualEdgeIteratorState virtualEdge)) {
            throw new IllegalStateException(
                    "virtual route edge does not expose its original directed edge key");
        }
        return virtualEdge.getOriginalEdgeKey();
    }

    private record GeometryTrace(
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> trace) {
    }
}
