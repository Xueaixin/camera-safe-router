package cn.camera.safe.routing;

import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
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
                mode == RoutePlanningMode.INTERNAL_SAFE ? leg : null,
                mode == RoutePlanningMode.EXTERNAL_ONLY ? leg : null,
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

        long started = System.nanoTime();
        EdgeKeyMultiTargetDijkstra.SearchResult search = EdgeKeyMultiTargetDijkstra.search(
                queryGraph,
                blockedWeighting,
                snap.getClosestNode(),
                searchPortals,
                direction == OUTBOUND ? FORWARD : REVERSE,
                properties.portalToleranceMeters(),
                properties.maxVisitedStates(),
                properties.searchTimeout());
        long searchMillis = (System.nanoTime() - started) / 1_000_000;
        rejectIncompleteSearch(search);

        List<ReferenceOption> options = new ArrayList<>();
        for (EdgeKeyMultiTargetDijkstra.PortalPath insidePath
                : search.candidates().values()) {
            SixthRingPortal portal = portalsById.get(insidePath.portal().id());
            if (portal == null) {
                throw new IllegalStateException("selected sixth-ring portal disappeared");
            }
            RouteLeg safeSegment;
            try {
                safeSegment = RouteGeometryAssembler.insideLeg(
                        queryGraph, queryDistanceWeighting, insidePath, direction);
            } catch (IllegalArgumentException exception) {
                continue;
            }

            EngineRoute referenceRoute;
            try {
                referenceRoute = direction == OUTBOUND
                        ? routingEngine.route(portal.crossing(), end, snapshot)
                        : routingEngine.route(start, portal.crossing(), snapshot);
            } catch (RoutingEngineException exception) {
                continue;
            }
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
                    referenceRoute));
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
        EngineRoute referenceRoute = selected.referenceRoute();
        return new PlannedRoute(
                direction == OUTBOUND
                        ? RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND
                        : RoutePlanningMode.CROSS_BOUNDARY_INBOUND,
                context.boundary().version(),
                direction,
                selected.portal(),
                selected.safeSegment(),
                selected.referenceSegment(),
                selected.distanceMeters(),
                selected.durationMillis(),
                selected.geometry(),
                audit.edgeChecks() + referenceRoute.searchEdgeChecks(),
                audit.virtualEdgeChecks() + referenceRoute.virtualEdgeChecks(),
                audit.blockedRejections() + referenceRoute.blockedRejections());
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
            EngineRoute referenceRoute) {
    }
}
