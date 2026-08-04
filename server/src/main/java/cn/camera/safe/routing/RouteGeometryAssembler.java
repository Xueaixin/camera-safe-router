package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;

import java.util.ArrayList;
import java.util.List;

final class RouteGeometryAssembler {
    private RouteGeometryAssembler() {
    }

    static RouteLeg insideLeg(
            Graph graph,
            Weighting weighting,
            EdgeKeyMultiTargetDijkstra.PortalPath path,
            SixthRingPortal.Direction direction) {
        List<Wgs84Coordinate> geometry = new ArrayList<>();
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
            appendDistinct(geometry, edgeSlice(edge, fromFraction, toFraction));
            double traversedFraction = Math.max(0, toFraction - fromFraction);
            long edgeMillis = Math.max(0, weighting.calcEdgeMillis(edge, false));
            durationMillis = saturatedAdd(
                    durationMillis, Math.round(edgeMillis * traversedFraction));
            previous = edge;
        }

        Wgs84Coordinate crossing = path.portal().coordinate();
        if (!geometry.isEmpty()) {
            if (direction == SixthRingPortal.Direction.OUTBOUND) {
                geometry.set(geometry.size() - 1, crossing);
            } else {
                geometry.set(0, crossing);
            }
        }
        return new RouteLeg(path.distanceMeters(), durationMillis, geometry);
    }

    @SafeVarargs
    static List<Wgs84Coordinate> join(List<Wgs84Coordinate>... parts) {
        List<Wgs84Coordinate> result = new ArrayList<>();
        for (List<Wgs84Coordinate> part : parts) {
            appendDistinct(result, part);
        }
        return List.copyOf(result);
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

    private static long saturatedAdd(long first, long second) {
        if (second > Long.MAX_VALUE - first) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }
}
