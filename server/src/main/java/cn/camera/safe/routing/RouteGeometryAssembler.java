package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RouteGeometryAssembler {
    private RouteGeometryAssembler() {
    }

    static TracedRouteLeg insideLeg(
            Graph graph,
            Weighting weighting,
            EdgeKeyMultiTargetDijkstra.PortalPath path,
            SixthRingPortal.Direction direction,
            int baseEdgeCount,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            EnumEncodedValue<RoadEnvironment> roadEnvironment) {
        List<Wgs84Coordinate> geometry = new ArrayList<>();
        List<RouteTracePoint> trace = new ArrayList<>();
        long durationMillis = 0;
        EdgeIteratorState previous = null;
        List<Integer> edgeKeys = path.edgeKeys();
        for (int index = 0; index < edgeKeys.size(); index++) {
            int edgeKey = edgeKeys.get(index);
            EdgeIteratorState edge = graph.getEdgeIteratorStateForKey(edgeKey);
            if (edge == null) {
                throw new IllegalStateException("route edge key was not found: " + edgeKey);
            }
            if (previous != null) {
                int viaNode = previous.getAdjNode();
                if (viaNode != edge.getBaseNode()) {
                    throw new IllegalStateException("route edge keys are not continuous");
                }
                durationMillis = saturatedAdd(durationMillis, Math.max(0,
                        weighting.calcTurnMillis(previous.getEdge(), viaNode, edge.getEdge())));
            }

            double fromFraction = 0;
            double toFraction = 1;
            if (edgeKey == path.terminalEdgeKey()) {
                if (direction == SixthRingPortal.Direction.OUTBOUND
                        && index == edgeKeys.size() - 1) {
                    toFraction = path.terminalFractionFromBase();
                } else if (direction == SixthRingPortal.Direction.INBOUND && index == 0) {
                    fromFraction = path.terminalFractionFromBase();
                }
            }
            appendTrace(
                    geometry,
                    trace,
                    edgeSlice(edge, fromFraction, toFraction),
                    edge,
                    baseEdgeCount,
                    roadClass,
                    roadClassLink,
                    roadEnvironment);
            double traversedFraction = Math.max(0, toFraction - fromFraction);
            long edgeMillis = Math.max(0, weighting.calcEdgeMillis(edge, false));
            durationMillis = saturatedAdd(
                    durationMillis, Math.round(edgeMillis * traversedFraction));
            previous = edge;
        }

        Wgs84Coordinate crossing = path.portal().coordinate();
        if (geometry.isEmpty()) {
            geometry.add(crossing);
            geometry.add(crossing);
        } else {
            if (direction == SixthRingPortal.Direction.OUTBOUND) {
                geometry.set(geometry.size() - 1, crossing);
            } else {
                geometry.set(0, crossing);
            }
        }
        List<RouteTracePoint> normalizedTrace = trace.stream()
                .map(point -> new RouteTracePoint(
                        point.geometryIndex(),
                        geometry.get(point.geometryIndex()),
                        point.roadName(),
                        point.roadClass(),
                        point.roadClassLink(),
                        point.roadEnvironment(),
                        point.originalEdgeKey()))
                .toList();
        return new TracedRouteLeg(
                new RouteLeg(path.distanceMeters(), durationMillis, geometry),
                normalizedTrace);
    }

    @SafeVarargs
    static List<Wgs84Coordinate> join(List<Wgs84Coordinate>... parts) {
        List<Wgs84Coordinate> result = new ArrayList<>();
        for (List<Wgs84Coordinate> part : parts) {
            appendDistinct(result, part);
        }
        return List.copyOf(result);
    }

    static PortalAnchoredRoute insertCrossing(
            PortalAnchoredRoute anchoredRoute,
            SixthRingPortal portal) {
        EngineRoute route = anchoredRoute.route();
        List<Wgs84Coordinate> geometry = new ArrayList<>(route.geometry());
        if (geometry.contains(portal.crossing())) {
            return anchoredRoute;
        }
        List<Integer> matchingSegments = java.util.stream.IntStream
                .range(1, geometry.size())
                .filter(index -> segmentUsesEdge(
                        anchoredRoute.trace(), index, portal.edgeKey()))
                .boxed()
                .sorted(Comparator.comparingDouble(index -> GeoDistance.minimumMeters(
                        portal.crossing(),
                        List.of(geometry.get(index - 1), geometry.get(index)))))
                .toList();
        if (matchingSegments.isEmpty()) {
            throw new IllegalStateException(
                    "physical boundary crossing is not on the anchored route edge trace");
        }
        int insertionIndex = matchingSegments.getFirst();
        double distance = GeoDistance.minimumMeters(
                portal.crossing(),
                List.of(geometry.get(insertionIndex - 1), geometry.get(insertionIndex)));
        if (distance > 2) {
            throw new IllegalStateException(
                    "physical boundary crossing is too far from the anchored route geometry");
        }
        geometry.add(insertionIndex, portal.crossing());

        RouteTracePoint template = anchoredRoute.trace().stream()
                .filter(point -> point.originalEdgeKey() == portal.edgeKey())
                .filter(point -> point.geometryIndex() == insertionIndex - 1
                        || point.geometryIndex() == insertionIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "physical boundary crossing edge metadata is missing"));
        List<RouteTracePoint> trace = new ArrayList<>(anchoredRoute.trace().size() + 1);
        boolean inserted = false;
        for (RouteTracePoint point : anchoredRoute.trace()) {
            if (!inserted && point.geometryIndex() >= insertionIndex) {
                trace.add(new RouteTracePoint(
                        insertionIndex,
                        portal.crossing(),
                        template.roadName(),
                        template.roadClass(),
                        template.roadClassLink(),
                        template.roadEnvironment(),
                        template.originalEdgeKey()));
                inserted = true;
            }
            trace.add(RouteTraceSupport.withGeometryIndex(
                    point,
                    point.geometryIndex() >= insertionIndex
                            ? point.geometryIndex() + 1 : point.geometryIndex()));
        }
        if (!inserted) {
            trace.add(new RouteTracePoint(
                    insertionIndex,
                    portal.crossing(),
                    template.roadName(),
                    template.roadClass(),
                    template.roadClassLink(),
                    template.roadEnvironment(),
                    template.originalEdgeKey()));
        }
        EngineRoute updated = new EngineRoute(
                route.distanceMeters(),
                route.durationMillis(),
                geometry,
                trace,
                route.searchEdgeChecks(),
                route.virtualEdgeChecks(),
                route.blockedRejections());
        return new PortalAnchoredRoute(updated, trace);
    }

    private static boolean segmentUsesEdge(
            List<RouteTracePoint> trace,
            int geometryIndex,
            int edgeKey) {
        boolean previous = trace.stream().anyMatch(point ->
                point.geometryIndex() == geometryIndex - 1
                        && point.originalEdgeKey() == edgeKey);
        return previous && trace.stream().anyMatch(point ->
                point.geometryIndex() == geometryIndex
                        && point.originalEdgeKey() == edgeKey);
    }

    private static List<Wgs84Coordinate> edgeSlice(
            EdgeIteratorState edge,
            double fromFraction,
            double toFraction) {
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        if (points.size() < 2) {
            return List.of();
        }
        double[] cumulative = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            cumulative[index] = cumulative[index - 1] + Math.hypot(
                    points.getLon(index) - points.getLon(index - 1),
                    points.getLat(index) - points.getLat(index - 1));
        }
        double total = cumulative[cumulative.length - 1];
        double from = total * Math.max(0, Math.min(1, fromFraction));
        double to = total * Math.max(0, Math.min(1, toFraction));
        List<Wgs84Coordinate> result = new ArrayList<>();
        result.add(pointAt(points, cumulative, from));
        for (int index = 1; index < points.size() - 1; index++) {
            if (cumulative[index] > from && cumulative[index] < to) {
                result.add(new Wgs84Coordinate(points.getLon(index), points.getLat(index)));
            }
        }
        result.add(pointAt(points, cumulative, to));
        return List.copyOf(result);
    }

    private static Wgs84Coordinate pointAt(
            PointList points,
            double[] cumulative,
            double target) {
        if (target <= 0) {
            return new Wgs84Coordinate(points.getLon(0), points.getLat(0));
        }
        int last = points.size() - 1;
        if (target >= cumulative[last]) {
            return new Wgs84Coordinate(points.getLon(last), points.getLat(last));
        }
        for (int index = 1; index < points.size(); index++) {
            if (cumulative[index] < target) {
                continue;
            }
            double segment = cumulative[index] - cumulative[index - 1];
            double fraction = segment == 0 ? 0 : (target - cumulative[index - 1]) / segment;
            return new Wgs84Coordinate(
                    points.getLon(index - 1)
                            + (points.getLon(index) - points.getLon(index - 1)) * fraction,
                    points.getLat(index - 1)
                            + (points.getLat(index) - points.getLat(index - 1)) * fraction);
        }
        throw new IllegalStateException("edge slice coordinate was not found");
    }

    private static void appendDistinct(
            List<Wgs84Coordinate> target,
            List<Wgs84Coordinate> source) {
        for (Wgs84Coordinate point : source) {
            if (target.isEmpty() || !target.getLast().equals(point)) {
                target.add(point);
            }
        }
    }

    private static void appendTrace(
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> trace,
            List<Wgs84Coordinate> points,
            EdgeIteratorState edge,
            int baseEdgeCount,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue roadClassLink,
            EnumEncodedValue<RoadEnvironment> roadEnvironment) {
        for (Wgs84Coordinate point : points) {
            int geometryIndex;
            if (!geometry.isEmpty() && geometry.getLast().equals(point)) {
                geometryIndex = geometry.size() - 1;
            } else {
                geometry.add(point);
                geometryIndex = geometry.size() - 1;
            }
            trace.add(new RouteTracePoint(
                    geometryIndex,
                    point,
                    edge.getName(),
                    edge.get(roadClass),
                    edge.get(roadClassLink),
                    edge.get(roadEnvironment),
                    OriginalEdgeKey.resolve(edge, baseEdgeCount)));
        }
    }

    private static long saturatedAdd(long first, long second) {
        if (second > Long.MAX_VALUE - first) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }
}
