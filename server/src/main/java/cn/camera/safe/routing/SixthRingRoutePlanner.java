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
import static cn.camera.safe.routing.SixthRingBoundary.Location.BOUNDARY;
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
    private static final BoundaryCrossingResolver BOUNDARY_CROSSING_RESOLVER =
            new BoundaryCrossingResolver();

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
        if (startLocation == BOUNDARY || endLocation == BOUNDARY) {
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
        EdgeTraversalConstraint referenceConstraint = RoadStateTraversalConstraint.released(
                context.boundary().controlledArea(),
                baseGraph.getEdges(),
                context.roadClassification());
        Set<String> evaluatedPortalIds = new HashSet<>();
        EdgeKeyMultiTargetDijkstra.SearchResult search;
        ReferenceOption selected;
        int tier = 1;
        do {
            double toleranceMeters = (double) properties.portalDistanceTierMeters() * tier;
            search = EdgeKeyMultiTargetDijkstra.search(
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

            List<ReferenceOption> tierOptions = new ArrayList<>();
            for (EdgeKeyMultiTargetDijkstra.PortalPath insidePath
                    : search.candidates().values()) {
                if (!evaluatedPortalIds.add(insidePath.portal().id())) {
                    continue;
                }
                ReferenceOption option = referenceOption(
                        direction,
                        start,
                        end,
                        snapshot,
                        context,
                        hopper,
                        queryGraph,
                        queryTimeWeighting,
                        roadClass,
                        roadClassLink,
                        roadEnvironment,
                        referenceConstraint,
                        releasesById,
                        insidePath);
                if (option != null) {
                    tierOptions.add(option);
                }
            }
            selected = tierOptions.stream()
                    .min(Comparator.comparingLong(ReferenceOption::durationMillis)
                            .thenComparingDouble(ReferenceOption::distanceMeters)
                            .thenComparingDouble(option -> option.safeSegment().distanceMeters())
                            .thenComparing(option -> option.releasePoint().id()))
                    .orElse(null);
            if (selected == null) {
                if (search.completion() == EdgeKeyMultiTargetDijkstra.Completion.EXHAUSTED) {
                    throw new SixthRingRouteException(
                            SixthRingRouteException.Reason.REFERENCE_ROUTE_FAILED,
                            "全部可达边界候选均无法生成连续的完整参考路线");
                }
                tier++;
            }
        } while (selected == null);
        long searchMillis = (System.nanoTime() - started) / 1_000_000;
        LOGGER.info("Control release selected type={} id={}",
                selected.releasePoint().type(), selected.releasePoint().id());

        LOGGER.info("跨界路线规划完成 方向={} 边界版本={} 通行口={} Dmin米={} "
                        + "受控侧距离米={} 完整时间秒={} 完整距离米={} 距离层={} "
                        + "已评估候选数={} 访问状态={} 总耗时毫秒={}",
                direction,
                context.boundary().version(),
                selected.boundaryCrossing().id(),
                Math.round(search.minimumDistanceMeters()),
                Math.round(selected.safeSegment().distanceMeters()),
                Math.round(selected.durationMillis() / 1_000.0),
                Math.round(selected.distanceMeters()),
                tier,
                evaluatedPortalIds.size(),
                search.visitedStates(),
                searchMillis);
        PortalAnchoredRoute anchoredRoute = selected.anchoredRoute();
        EngineRoute referenceRoute = anchoredRoute.route();
        NavigationHandoffPoint navigationHandoff = NAVIGATION_HANDOFF_SELECTOR.select(
                direction,
                context.boundary(),
                selected.boundaryCrossing(),
                context.roadClassification(),
                anchoredRoute).orElse(null);
        ExternalHandoffPoint externalHandoff = navigationHandoff == null
                ? null : EXTERNAL_HANDOFF_SELECTOR.select(navigationHandoff);
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
                selected.boundaryCrossing(),
                navigationHandoff,
                segments.safeSegment(),
                segments.referenceSegment(),
                externalHandoff,
                selected.distanceMeters(),
                selected.durationMillis(),
                selected.geometry(),
                selected.trace(),
                audit.edgeChecks() + referenceRoute.searchEdgeChecks(),
                audit.virtualEdgeChecks() + referenceRoute.virtualEdgeChecks(),
                audit.blockedRejections() + referenceRoute.blockedRejections());
    }

    private ReferenceOption referenceOption(
            SixthRingPortal.Direction direction,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot,
            SixthRingRoutingContext context,
            HardAvoidingGraphHopper hopper,
            QueryGraph queryGraph,
            Weighting queryTimeWeighting,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            EnumEncodedValue<RoadEnvironment> roadEnvironment,
            EdgeTraversalConstraint referenceConstraint,
            Map<String, ControlReleasePoint> releasesById,
            EdgeKeyMultiTargetDijkstra.PortalPath insidePath) {
        ControlReleasePoint releasePoint = releasesById.get(insidePath.portal().id());
        if (releasePoint == null) {
            throw new IllegalStateException("selected control release point disappeared");
        }
        TracedRouteLeg safeRoute;
        try {
            safeRoute = RouteGeometryAssembler.insideLeg(
                    queryGraph,
                    queryTimeWeighting,
                    insidePath,
                    direction,
                    hopper.getBaseGraph().getEdges(),
                    roadClass,
                    roadClassLink,
                    roadEnvironment);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        RouteLeg safeSegment = safeRoute.leg();

        PortalAnchoredRoute anchoredRoute;
        try {
            anchoredRoute = PortalAnchoredRouteFinder.route(
                    hopper,
                    releasePoint,
                    direction == OUTBOUND ? end : start,
                    snapshot,
                    referenceConstraint,
                    context.routingWeighting(),
                    properties.maxVisitedStates(),
                    properties.searchTimeout());
        } catch (RoutingEngineException exception) {
            return null;
        }
        List<RouteTracePoint> initialTrace = actualDirectionTrace(
                direction, safeRoute, anchoredRoute);
        SixthRingPortal boundaryCrossing;
        try {
            boundaryCrossing = BOUNDARY_CROSSING_RESOLVER.resolve(
                    direction, context.topology(), initialTrace);
            anchoredRoute = RouteGeometryAssembler.insertCrossing(
                    anchoredRoute, boundaryCrossing);
        } catch (IllegalStateException exception) {
            return null;
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
        List<RouteTracePoint> fullTrace = actualDirectionTrace(
                direction, safeRoute, anchoredRoute);
        if (joinGap > properties.maxJoinGapMeters()
                || !ROUTE_SHAPE_VALIDATOR.isValid(
                direction,
                context.boundary(),
                fullTrace,
                context.roadClassification(),
                context.tollCorridors(),
                context.interchangeTopology())) {
            return null;
        }
        List<Wgs84Coordinate> fullGeometry = direction == OUTBOUND
                ? RouteGeometryAssembler.join(
                        safeSegment.geometry(), referenceSegment.geometry())
                : RouteGeometryAssembler.join(
                        referenceSegment.geometry(), safeSegment.geometry());
        return new ReferenceOption(
                releasePoint,
                boundaryCrossing,
                safeSegment,
                referenceSegment,
                safeSegment.distanceMeters() + referenceSegment.distanceMeters(),
                safeSegment.durationMillis() + referenceSegment.durationMillis(),
                fullGeometry,
                fullTrace,
                anchoredRoute);
    }

    private static List<RouteTracePoint> actualDirectionTrace(
            SixthRingPortal.Direction direction,
            TracedRouteLeg safeRoute,
            PortalAnchoredRoute anchoredRoute) {
        if (direction == OUTBOUND) {
            return RouteTraceSupport.join(
                    safeRoute.trace(),
                    safeRoute.leg().geometry().size(),
                    anchoredRoute.trace());
        }
        return RouteTraceSupport.join(
                anchoredRoute.trace(),
                anchoredRoute.route().geometry().size(),
                safeRoute.trace());
    }

    private static HandoffSegments splitAtNavigationHandoff(
            SixthRingPortal.Direction direction,
            RouteLeg safeSegment,
            RouteLeg referenceSegment,
            NavigationHandoffPoint handoff) {
        if (handoff.segment() == NavigationHandoffPoint.Segment.REFERENCE) {
            SplitLeg split = splitLeg(referenceSegment, handoff);
            if (direction == OUTBOUND) {
                return new HandoffSegments(
                        joinLegs(safeSegment, split.prefix()),
                        split.suffix());
            }
            return new HandoffSegments(
                    joinLegs(split.suffix(), safeSegment),
                    split.prefix());
        }

        SplitLeg split = splitLeg(safeSegment, handoff);
        if (direction == OUTBOUND) {
            return new HandoffSegments(
                    split.prefix(),
                    joinLegs(split.suffix(), referenceSegment));
        }
        return new HandoffSegments(
                split.suffix(),
                joinLegs(referenceSegment, split.prefix()));
    }

    private static SplitLeg splitLeg(RouteLeg leg, NavigationHandoffPoint handoff) {
        int handoffIndex = handoff.geometryIndex();
        List<Wgs84Coordinate> geometry = leg.geometry();
        if (handoffIndex <= 0 || handoffIndex >= geometry.size()
                || (handoffIndex == geometry.size() - 1
                        && handoff.fractionFromPrevious() >= 1)) {
            throw new IllegalArgumentException("navigation handoff cannot be a segment endpoint");
        }
        double totalGeometryMeters = geometryDistance(geometry, 0, geometry.size() - 1);
        double prefixGeometryMeters = geometryDistance(geometry, 0, handoffIndex - 1)
                + GeoDistance.meters(geometry.get(handoffIndex - 1), handoff.coordinate());
        double prefixRatio = totalGeometryMeters == 0
                ? (double) handoffIndex / (geometry.size() - 1)
                : prefixGeometryMeters / totalGeometryMeters;
        double prefixDistanceMeters = leg.distanceMeters() * prefixRatio;
        long prefixDurationMillis = Math.round(leg.durationMillis() * prefixRatio);
        List<Wgs84Coordinate> prefixGeometry = new ArrayList<>(
                geometry.subList(0, handoffIndex));
        prefixGeometry.add(handoff.coordinate());
        List<Wgs84Coordinate> suffixGeometry = new ArrayList<>();
        suffixGeometry.add(handoff.coordinate());
        int suffixStart = handoff.fractionFromPrevious() >= 1
                ? handoffIndex + 1 : handoffIndex;
        suffixGeometry.addAll(geometry.subList(suffixStart, geometry.size()));
        RouteLeg prefix = new RouteLeg(
                prefixDistanceMeters,
                prefixDurationMillis,
                prefixGeometry);
        RouteLeg suffix = new RouteLeg(
                leg.distanceMeters() - prefixDistanceMeters,
                leg.durationMillis() - prefixDurationMillis,
                suffixGeometry);
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
            ControlReleasePoint releasePoint,
            SixthRingPortal boundaryCrossing,
            RouteLeg safeSegment,
            RouteLeg referenceSegment,
            double distanceMeters,
            long durationMillis,
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> trace,
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
