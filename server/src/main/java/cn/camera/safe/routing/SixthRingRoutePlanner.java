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
import java.util.List;
import java.util.Map;

import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.INTERRUPTED;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.MAX_VISITED_STATES;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.TIMEOUT;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.FORWARD;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.REVERSE;
import static cn.camera.safe.routing.SixthRingBoundary.Location.BOUNDARY_BAND;
import static cn.camera.safe.routing.SixthRingBoundary.Location.INSIDE;
import static cn.camera.safe.routing.SixthRingBoundary.Location.OUTSIDE;
import static cn.camera.safe.routing.SixthRingPortal.Direction.INBOUND;
import static cn.camera.safe.routing.SixthRingPortal.Direction.OUTBOUND;

@Component
public final class SixthRingRoutePlanner implements RoutePlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SixthRingRoutePlanner.class);
    private static final SixthRingRouteShapeValidator ROUTE_SHAPE_VALIDATOR =
            new SixthRingRouteShapeValidator();
    private static final ExternalHandoffSelector EXTERNAL_HANDOFF_SELECTOR =
            new ExternalHandoffSelector();
    private static final NavigationHandoffSelector NAVIGATION_HANDOFF_SELECTOR =
            new NavigationHandoffSelector();

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
        if (startLocation == BOUNDARY_BAND || endLocation == BOUNDARY_BAND) {
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
        EngineRoute route = routingEngine.route(start, end, snapshot);
        RouteLeg leg = new RouteLeg(
                route.distanceMeters(), route.durationMillis(), route.geometry());
        return new PlannedRoute(
                mode,
                boundaryVersion,
                null,
                null,
                null,
                mode == RoutePlanningMode.INTERNAL_SAFE ? leg : null,
                mode == RoutePlanningMode.EXTERNAL_ONLY ? leg : null,
                null,
                route.distanceMeters(),
                route.durationMillis(),
                route.geometry(),
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
        Weighting queryDistanceWeighting = queryGraph.wrapWeighting(context.distanceWeighting());
        SearchAudit audit = new SearchAudit();
        Weighting blockedWeighting = new BlockedEdgeWeighting(
                queryDistanceWeighting,
                snapshot.blockedEdges(),
                audit,
                baseGraph.getEdges());

        List<SixthRingPortal> portals = direction == OUTBOUND
                ? context.topology().outbound().portals()
                : context.topology().inbound().portals();
        Map<String, SixthRingPortal> portalsById = new HashMap<>();
        List<EdgeKeyMultiTargetDijkstra.Portal> searchPortals = portals.stream()
                .peek(portal -> portalsById.put(portal.id(), portal))
                .map(portal -> new EdgeKeyMultiTargetDijkstra.Portal(
                        portal.id(),
                        portal.edgeKey(),
                        portal.fractionFromBase(),
                        portal.crossing()))
                .toList();
        EdgeTraversalConstraint insideConstraint = direction == OUTBOUND
                ? BoundaryTraversalConstraint.stayWithin(
                        context.boundary().outerPolygon(), baseGraph.getEdges())
                : BoundaryTraversalConstraint.stayWithin(
                        context.boundary().innerPolygon(), baseGraph.getEdges());

        long started = System.nanoTime();
        EdgeKeyMultiTargetDijkstra.SearchResult search = EdgeKeyMultiTargetDijkstra.search(
                queryGraph,
                blockedWeighting,
                snap.getClosestNode(),
                searchPortals,
                direction == OUTBOUND ? FORWARD : REVERSE,
                insideConstraint,
                properties.portalToleranceMeters(),
                properties.maxVisitedStates(),
                properties.searchTimeout());
        long searchMillis = (System.nanoTime() - started) / 1_000_000;
        rejectIncompleteSearch(search);

        List<ReferenceOption> options = new ArrayList<>();
        EdgeTraversalConstraint referenceConstraint =
                BoundaryTraversalConstraint.avoidInterior(
                        context.boundary().innerPolygon(), baseGraph.getEdges());
        for (EdgeKeyMultiTargetDijkstra.PortalPath insidePath
                : search.candidates().values()) {
            SixthRingPortal portal = portalsById.get(insidePath.portal().id());
            if (portal == null) {
                throw new IllegalStateException("selected sixth-ring portal disappeared");
            }
            TracedRouteLeg safeRoute;
            try {
                safeRoute = RouteGeometryAssembler.insideLeg(
                        queryGraph,
                        queryDistanceWeighting,
                        insidePath,
                        direction,
                        roadClass,
                        roadClassLink,
                        roadEnvironment);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            RouteLeg safeSegment = safeRoute.leg();

            PortalAnchoredRoute anchoredRoute;
            try {
                anchoredRoute = PortalAnchoredRouteFinder.route(
                        hopper,
                        portal,
                        direction == OUTBOUND ? end : start,
                        snapshot,
                        referenceConstraint,
                        context.distanceWeighting(),
                        properties.maxVisitedStates(),
                        properties.searchTimeout());
            } catch (RoutingEngineException exception) {
                continue;
            }
            EngineRoute referenceRoute = anchoredRoute.route();
            RouteLeg referenceSegment = new RouteLeg(
                    referenceRoute.distanceMeters(),
                    referenceRoute.durationMillis(),
                    referenceRoute.geometry());
            double joinGap = direction == OUTBOUND
                    ? GeoDistance.meters(
                            safeSegment.geometry().getLast(),
                            referenceSegment.geometry().getFirst())
                    : GeoDistance.meters(
                            referenceSegment.geometry().getLast(),
                            safeSegment.geometry().getFirst());
            if (joinGap > properties.maxJoinGapMeters()) {
                continue;
            }
            if (!ROUTE_SHAPE_VALIDATOR.isValid(
                    direction, context.boundary(), safeSegment, referenceSegment)) {
                continue;
            }
            List<Wgs84Coordinate> fullGeometry = direction == OUTBOUND
                    ? RouteGeometryAssembler.join(
                            safeSegment.geometry(), referenceSegment.geometry())
                    : RouteGeometryAssembler.join(
                            referenceSegment.geometry(), safeSegment.geometry());
            options.add(new ReferenceOption(
                    portal,
                    safeSegment,
                    referenceSegment,
                    safeSegment.distanceMeters() + referenceSegment.distanceMeters(),
                    safeSegment.durationMillis() + referenceSegment.durationMillis(),
                    fullGeometry,
                    safeRoute.trace(),
                    anchoredRoute));
        }
        ReferenceOption selected = options.stream()
                .min(Comparator.comparingDouble(ReferenceOption::distanceMeters)
                        .thenComparingLong(ReferenceOption::durationMillis)
                        .thenComparing(option -> option.portal().id()))
                .orElseThrow(() -> new SixthRingRouteException(
                        SixthRingRouteException.Reason.REFERENCE_ROUTE_FAILED,
                        "候选通行口可达，但无法生成连续的完整参考路线"));

        LOGGER.info("六环跨界路线规划完成 方向={} 边界版本={} 通行口={} Dmin米={} "
                        + "环内距离米={} 完整距离米={} 候选数={} 访问状态={} 搜索耗时毫秒={}",
                direction,
                context.boundary().version(),
                selected.portal().id(),
                Math.round(search.minimumDistanceMeters()),
                Math.round(selected.safeSegment().distanceMeters()),
                Math.round(selected.distanceMeters()),
                search.candidates().size(),
                search.visitedStates(),
                searchMillis);
        PortalAnchoredRoute anchoredRoute = selected.anchoredRoute();
        EngineRoute referenceRoute = anchoredRoute.route();
        ExternalHandoffPoint externalHandoff = EXTERNAL_HANDOFF_SELECTOR.select(
                direction,
                context.boundary(),
                selected.referenceSegment().geometry());
        NavigationHandoffPoint navigationHandoff = NAVIGATION_HANDOFF_SELECTOR.select(
                direction,
                context.boundary(),
                new TracedRouteLeg(selected.safeSegment(), selected.safeTrace()),
                anchoredRoute).orElse(null);
        HandoffSegments segments = navigationHandoff == null
                ? new HandoffSegments(selected.safeSegment(), selected.referenceSegment())
                : splitAtNavigationHandoff(
                        direction,
                        selected.safeSegment(),
                        selected.referenceSegment(),
                        navigationHandoff);
        return new PlannedRoute(
                direction == OUTBOUND
                        ? RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND
                        : RoutePlanningMode.CROSS_BOUNDARY_INBOUND,
                context.boundary().version(),
                direction,
                selected.portal(),
                navigationHandoff,
                segments.safeSegment(),
                segments.referenceSegment(),
                externalHandoff,
                selected.distanceMeters(),
                selected.durationMillis(),
                selected.geometry(),
                audit.edgeChecks() + referenceRoute.searchEdgeChecks(),
                audit.virtualEdgeChecks() + referenceRoute.virtualEdgeChecks(),
                audit.blockedRejections() + referenceRoute.blockedRejections());
    }

    private static HandoffSegments splitAtNavigationHandoff(
            SixthRingPortal.Direction direction,
            RouteLeg safeSegment,
            RouteLeg referenceSegment,
            NavigationHandoffPoint handoff) {
        if (handoff.segment() == NavigationHandoffPoint.Segment.REFERENCE) {
            SplitLeg split = splitLeg(referenceSegment, handoff.geometryIndex());
            if (direction == OUTBOUND) {
                return new HandoffSegments(
                        joinLegs(safeSegment, split.prefix()),
                        split.suffix());
            }
            return new HandoffSegments(
                    joinLegs(split.suffix(), safeSegment),
                    split.prefix());
        }

        SplitLeg split = splitLeg(safeSegment, handoff.geometryIndex());
        if (direction == OUTBOUND) {
            return new HandoffSegments(
                    split.prefix(),
                    joinLegs(split.suffix(), referenceSegment));
        }
        return new HandoffSegments(
                split.suffix(),
                joinLegs(referenceSegment, split.prefix()));
    }

    private static SplitLeg splitLeg(RouteLeg leg, int handoffIndex) {
        List<Wgs84Coordinate> geometry = leg.geometry();
        if (handoffIndex <= 0 || handoffIndex >= geometry.size() - 1) {
            throw new IllegalArgumentException("navigation handoff cannot be a segment endpoint");
        }
        double totalGeometryMeters = geometryDistance(geometry, 0, geometry.size() - 1);
        double prefixGeometryMeters = geometryDistance(geometry, 0, handoffIndex);
        double prefixRatio = totalGeometryMeters == 0
                ? (double) handoffIndex / (geometry.size() - 1)
                : prefixGeometryMeters / totalGeometryMeters;
        double prefixDistanceMeters = leg.distanceMeters() * prefixRatio;
        long prefixDurationMillis = Math.round(leg.durationMillis() * prefixRatio);
        RouteLeg prefix = new RouteLeg(
                prefixDistanceMeters,
                prefixDurationMillis,
                geometry.subList(0, handoffIndex + 1));
        RouteLeg suffix = new RouteLeg(
                leg.distanceMeters() - prefixDistanceMeters,
                leg.durationMillis() - prefixDurationMillis,
                geometry.subList(handoffIndex, geometry.size()));
        return new SplitLeg(prefix, suffix);
    }

    private static RouteLeg joinLegs(RouteLeg first, RouteLeg second) {
        return new RouteLeg(
                first.distanceMeters() + second.distanceMeters(),
                first.durationMillis() + second.durationMillis(),
                RouteGeometryAssembler.join(first.geometry(), second.geometry()));
    }

    private static double geometryDistance(
            List<Wgs84Coordinate> geometry,
            int fromIndex,
            int toIndex) {
        double distance = 0;
        for (int index = fromIndex + 1; index <= toIndex; index++) {
            distance += GeoDistance.meters(geometry.get(index - 1), geometry.get(index));
        }
        return distance;
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

    private record ReferenceOption(
            SixthRingPortal portal,
            RouteLeg safeSegment,
            RouteLeg referenceSegment,
            double distanceMeters,
            long durationMillis,
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> safeTrace,
            PortalAnchoredRoute anchoredRoute) {
    }

    private record HandoffSegments(
            RouteLeg safeSegment,
            RouteLeg referenceSegment) {
    }

    private record SplitLeg(
            RouteLeg prefix,
            RouteLeg suffix) {
    }
}
