package cn.camera.safe.routing;

import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadClassLink;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.Snap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.INTERRUPTED;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.MAX_VISITED_STATES;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.TIMEOUT;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.FORWARD;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.REVERSE;
import static cn.camera.safe.routing.SixthRingBoundary.Location.INSIDE;
import static cn.camera.safe.routing.SixthRingBoundary.Location.OUTSIDE;
import static cn.camera.safe.routing.SixthRingPortal.Direction.INBOUND;
import static cn.camera.safe.routing.SixthRingPortal.Direction.OUTBOUND;

@Component
public final class SixthRingRoutePlanner implements RoutePlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SixthRingRoutePlanner.class);
    private static final ExternalHandoffSelector EXTERNAL_HANDOFF_SELECTOR =
            new ExternalHandoffSelector();
    private static final OuterHandoffWalker OUTER_HANDOFF_WALKER = new OuterHandoffWalker();
    private static final double OUTBOUND_DIRECTION_THRESHOLD_DEGREES = 135;
    private static final double INBOUND_DIRECTION_THRESHOLD_DEGREES = 90;
    private static final double FALLBACK_DIRECTION_THRESHOLD_DEGREES = 180;
    private static final int MAX_DISTANCE_TIERS = 5;

    private final GraphHopperManager graphManager;
    private final SixthRingRoutingManager sixthRingManager;
    private final RoutingEngine routingEngine;
    private final SixthRingProperties properties;

    public SixthRingRoutePlanner(
            GraphHopperManager graphManager,
            SixthRingRoutingManager sixthRingManager,
            RoutingEngine routingEngine,
            SixthRingProperties properties) {
        this.graphManager = graphManager;
        this.sixthRingManager = sixthRingManager;
        this.routingEngine = routingEngine;
        this.properties = properties;
    }

    @Override
    public PlannedRoute plan(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        SixthRingRoutingContext context;
        try {
            context = sixthRingManager.requireContext();
        } catch (IllegalStateException exception) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.TOPOLOGY_NOT_READY,
                    "六环路由拓扑尚未就绪",
                    exception);
        }

        SixthRingBoundary.Location startLocation = context.boundary().locate(start);
        SixthRingBoundary.Location endLocation = context.boundary().locate(end);
        if (startLocation == SixthRingBoundary.Location.BOUNDARY
                || endLocation == SixthRingBoundary.Location.BOUNDARY) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.BOUNDARY_AMBIGUOUS,
                    "起点或终点位于六环边界带，请调整点位后重试");
        }
        if (startLocation == INSIDE && endLocation == INSIDE) {
            return standardRoute(
                    RoutePlanningMode.INTERNAL_SAFE,
                    context.boundary().version(),
                    start,
                    end,
                    snapshot);
        }
        if (startLocation == OUTSIDE && endLocation == OUTSIDE) {
            return standardRoute(
                    RoutePlanningMode.EXTERNAL_ONLY,
                    context.boundary().version(),
                    start,
                    end,
                    snapshot);
        }
        return crossBoundaryRoute(
                startLocation == INSIDE ? OUTBOUND : INBOUND,
                start,
                end,
                snapshot,
                context);
    }

    private PlannedRoute standardRoute(
            RoutePlanningMode mode,
            String boundaryVersion,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        if (mode == RoutePlanningMode.EXTERNAL_ONLY) {
            return new PlannedRoute(
                    mode,
                    boundaryVersion,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    List.of(start, end),
                    0,
                    0,
                    0);
        }
        EngineRoute route = routingEngine.route(start, end, snapshot);
        RouteLeg leg = new RouteLeg(
                route.distanceMeters(), route.durationMillis(), route.geometry());
        return new PlannedRoute(
                mode,
                boundaryVersion,
                null,
                null,
                null,
                leg,
                null,
                null,
                route.distanceMeters(),
                route.durationMillis(),
                route.geometry(),
                route.trace(),
                route.searchEdgeChecks(),
                route.virtualEdgeChecks(),
                route.blockedRejections());
    }

    private PlannedRoute crossBoundaryRoute(
            SixthRingPortal.Direction direction,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot,
            SixthRingRoutingContext context) {
        Wgs84Coordinate insidePoint = direction == OUTBOUND ? start : end;
        HardAvoidingGraphHopper hopper = graphManager.requireHopper();
        BaseGraph baseGraph = hopper.getBaseGraph();
        BooleanEncodedValue carAccess = hopper.getEncodingManager()
                .getBooleanEncodedValue("car_access");
        EnumEncodedValue<RoadClass> roadClass = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        BooleanEncodedValue roadClassLink = hopper.getEncodingManager()
                .getBooleanEncodedValue(RoadClassLink.KEY);
        EnumEncodedValue<RoadEnvironment> roadEnvironment = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class);
        EdgeFilter snapFilter = edge -> edge.get(carAccess) || edge.getReverse(carAccess);
        Snap snap = hopper.getLocationIndex().findClosest(
                insidePoint.lat(), insidePoint.lng(), snapFilter);
        if (!snap.isValid() || snap.getQueryDistance() > properties.maxSnapDistanceMeters()) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.POINT_NOT_FOUND,
                    "六环内点无法可靠吸附到当前驾车路网");
        }

        QueryGraph queryGraph = QueryGraph.create(baseGraph, snap);
        Weighting queryTimeWeighting = queryGraph.wrapWeighting(context.controlledTimeWeighting());
        SearchAudit audit = new SearchAudit();
        Weighting blockedWeighting = new BlockedEdgeWeighting(
                queryTimeWeighting,
                snapshot.blockedEdges(),
                audit,
                baseGraph.getEdges());

        List<ControlReleasePoint> releasePoints = new ArrayList<>(direction == OUTBOUND
                ? context.releaseTopology().outbound()
                : context.releaseTopology().inbound());
        Map<String, ControlReleasePoint> releasesById = new HashMap<>();
        List<EdgeKeyMultiTargetDijkstra.Portal> searchPortals = releasePoints.stream()
                .peek(point -> releasesById.put(point.id(), point))
                .map(point -> new EdgeKeyMultiTargetDijkstra.Portal(
                        point.id(),
                        point.edgeKey(),
                        point.fractionFromBase(),
                        point.coordinate()))
                .toList();
        EdgeTraversalConstraint insideConstraint = RoadStateTraversalConstraint.controlled(
                context.boundary().controlledArea(),
                baseGraph.getEdges(),
                context.roadClassification());

        long started = System.nanoTime();
        Set<String> evaluatedPortalIds = new HashSet<>();
        List<EdgeKeyMultiTargetDijkstra.PortalPath> accumulatedCandidates = new ArrayList<>();
        ScoredCandidate selected = null;
        int selectedTier = 0;
        for (int tier = 1; tier <= MAX_DISTANCE_TIERS; tier++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new SixthRingRouteException(
                        SixthRingRouteException.Reason.SEARCH_TIMEOUT,
                        "路线计算被中断");
            }
            double toleranceMeters = (double) properties.portalDistanceTierMeters() * tier;
            EdgeKeyMultiTargetDijkstra.SearchResult search = EdgeKeyMultiTargetDijkstra.search(
                    queryGraph,
                    blockedWeighting,
                    snap.getClosestNode(),
                    searchPortals,
                    direction == OUTBOUND ? FORWARD : REVERSE,
                    insideConstraint,
                    toleranceMeters,
                    properties.maxVisitedStates(),
                    properties.searchTimeout());
            rejectIncompleteSearch(search);
            List<EdgeKeyMultiTargetDijkstra.PortalPath> newCandidates =
                    search.candidates().values().stream()
                            .filter(insidePath -> evaluatedPortalIds.add(
                                    insidePath.portal().id()))
                            .toList();
            accumulatedCandidates.addAll(newCandidates);
            selected = selectBest(
                    direction, start, end, newCandidates, releasesById);
            if (selected != null) {
                selectedTier = tier;
                break;
            }
        }
        if (selected == null) {
            selected = selectBest(
                    direction,
                    start,
                    end,
                    accumulatedCandidates,
                    releasesById,
                    FALLBACK_DIRECTION_THRESHOLD_DEGREES);
        }
        if (selected == null) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.NO_ROUTE,
                    "未找到方向合理且可达的六环通行口");
        }
        long searchMillis = (System.nanoTime() - started) / 1_000_000;

        TracedRouteLeg safeRoute = RouteGeometryAssembler.insideLeg(
                queryGraph,
                queryTimeWeighting,
                selected.portalPath(),
                direction,
                baseGraph.getEdges(),
                roadClass,
                roadClassLink,
                roadEnvironment);
        OuterHandoffWalker.Result handoffResult = OUTER_HANDOFF_WALKER.walk(
                baseGraph,
                context.boundary(),
                context.roadClassification(),
                carAccess,
                selected.releasePoint()).orElseThrow(() -> new SixthRingRouteException(
                SixthRingRouteException.Reason.REFERENCE_ROUTE_FAILED,
                "通行口界外无法确定高德导航交接点"));
        RouteLeg safeSegment = assembleSafeSegment(
                direction, safeRoute.leg(), handoffResult);
        Wgs84Coordinate handoffCoordinate =
                handoffResult.path().get(handoffResult.path().size() - 1);
        int handoffIndex = direction == OUTBOUND
                ? safeSegment.geometry().size() - 1 : 1;
        NavigationHandoffPoint navigationHandoff = new NavigationHandoffPoint(
                handoffCoordinate,
                handoffResult.clearanceMeters(),
                handoffResult.roadName(),
                NavigationHandoffPoint.Segment.SAFE,
                handoffIndex);
        ExternalHandoffPoint externalHandoff =
                EXTERNAL_HANDOFF_SELECTOR.select(navigationHandoff);
        SixthRingPortal boundaryCrossing =
                selected.releasePoint().physicalPortalOptional().orElseThrow(
                        () -> new IllegalStateException(
                                "selected control release point lost its physical portal"));

        LOGGER.info("跨界路线规划完成 方向={} 边界版本={} 通行口={} Dmin米={} "
                        + "受控侧距离米={} 交接点净空米={} 距离层={} "
                        + "已评估候选数={} 总耗时毫秒={}",
                direction,
                context.boundary().version(),
                boundaryCrossing.id(),
                Math.round(selected.portalPath().distanceMeters()),
                Math.round(safeSegment.distanceMeters()),
                Math.round(handoffResult.clearanceMeters()),
                selectedTier,
                evaluatedPortalIds.size(),
                searchMillis);
        return new PlannedRoute(
                direction == OUTBOUND
                        ? RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND
                        : RoutePlanningMode.CROSS_BOUNDARY_INBOUND,
                context.boundary().version(),
                direction,
                boundaryCrossing,
                navigationHandoff,
                safeSegment,
                null,
                externalHandoff,
                safeSegment.distanceMeters(),
                safeSegment.durationMillis(),
                safeSegment.geometry(),
                safeRoute.trace(),
                audit.edgeChecks(),
                audit.virtualEdgeChecks(),
                audit.blockedRejections());
    }

    private static RouteLeg assembleSafeSegment(
            SixthRingPortal.Direction direction,
            RouteLeg insideLeg,
            OuterHandoffWalker.Result handoff) {
        List<Wgs84Coordinate> extension = handoff.path();
        double extensionMeters = handoff.routeDistanceMeters();
        long extensionMillis = Math.round(extensionMeters / 16.67);
        if (direction == OUTBOUND) {
            return new RouteLeg(
                    insideLeg.distanceMeters() + extensionMeters,
                    insideLeg.durationMillis() + extensionMillis,
                    RouteGeometryAssembler.join(insideLeg.geometry(), extension));
        }
        List<Wgs84Coordinate> reversedExtension = new ArrayList<>(extension);
        java.util.Collections.reverse(reversedExtension);
        return new RouteLeg(
                insideLeg.distanceMeters() + extensionMeters,
                insideLeg.durationMillis() + extensionMillis,
                RouteGeometryAssembler.join(reversedExtension, insideLeg.geometry()));
    }

    private ScoredCandidate selectBest(
            SixthRingPortal.Direction direction,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            List<EdgeKeyMultiTargetDijkstra.PortalPath> candidates,
            Map<String, ControlReleasePoint> releasesById) {
        double threshold = direction == OUTBOUND
                ? OUTBOUND_DIRECTION_THRESHOLD_DEGREES
                : INBOUND_DIRECTION_THRESHOLD_DEGREES;
        return selectBest(direction, start, end, candidates, releasesById, threshold);
    }

    private ScoredCandidate selectBest(
            SixthRingPortal.Direction direction,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            List<EdgeKeyMultiTargetDijkstra.PortalPath> candidates,
            Map<String, ControlReleasePoint> releasesById,
            double thresholdDegrees) {
        ScoredCandidate best = null;
        for (EdgeKeyMultiTargetDijkstra.PortalPath candidate : candidates) {
            ControlReleasePoint releasePoint = releasesById.get(candidate.portal().id());
            if (releasePoint == null) {
                continue;
            }
            double delta = directionDeltaDegrees(direction, start, end, releasePoint);
            if (delta > thresholdDegrees) {
                continue;
            }
            double score = candidate.distanceMeters()
                    + straightLineMeters(direction, start, end, releasePoint);
            if (best == null
                    || score < best.score()
                    || (score == best.score()
                    && (candidate.distanceMeters() < best.portalPath().distanceMeters()
                    || (candidate.distanceMeters() == best.portalPath().distanceMeters()
                    && releasePoint.id().compareTo(best.releasePoint().id()) < 0)))) {
                best = new ScoredCandidate(candidate, releasePoint, delta, score);
            }
        }
        return best;
    }

    private static double straightLineMeters(
            SixthRingPortal.Direction direction,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            ControlReleasePoint releasePoint) {
        Wgs84Coordinate outer = direction == OUTBOUND ? end : start;
        return GeoDistance.meters(releasePoint.coordinate(), outer);
    }

    private static double directionDeltaDegrees(
            SixthRingPortal.Direction direction,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            ControlReleasePoint releasePoint) {
        Wgs84Coordinate inside = direction == OUTBOUND ? start : end;
        Wgs84Coordinate outer = direction == OUTBOUND ? end : start;
        double referenceBearing = direction == OUTBOUND
                ? bearingDegrees(inside, outer)
                : bearingDegrees(outer, inside);
        double candidateBearing = direction == OUTBOUND
                ? bearingDegrees(inside, releasePoint.coordinate())
                : bearingDegrees(outer, releasePoint.coordinate());
        double delta = Math.abs(normalizeDegrees(referenceBearing - candidateBearing));
        return Math.min(delta, 360 - delta);
    }

    private static double bearingDegrees(
            Wgs84Coordinate from, Wgs84Coordinate to) {
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }
    private static void rejectIncompleteSearch(
            EdgeKeyMultiTargetDijkstra.SearchResult search) {
        if (search.completion() == TIMEOUT || search.completion() == INTERRUPTED) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.SEARCH_TIMEOUT,
                    "六环通行口搜索超时");
        }
        if (search.completion() == MAX_VISITED_STATES) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.RESOURCE_LIMIT,
                    "六环通行口搜索达到访问状态上限");
        }
        if (search.provenNoRoute() || search.candidates().isEmpty()) {
            throw new SixthRingRouteException(
                    SixthRingRouteException.Reason.NO_ROUTE,
                    "未找到方向正确且可达的六环通行口");
        }
    }

    private record ScoredCandidate(
            EdgeKeyMultiTargetDijkstra.PortalPath portalPath,
            ControlReleasePoint releasePoint,
            double directionDeltaDegrees,
            double score) {
    }
}
