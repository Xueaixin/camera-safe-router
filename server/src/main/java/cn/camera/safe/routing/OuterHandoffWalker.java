package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 在通行口所在边界边及其界外“释放连接器”道路网内向外行走，寻找高德导航交接点。
 *
 * <p>规则：沿路最多 500m，优先返回边界净空 ≥250m 的界外点；找不到时返回沿路范围内
 * 净空最大的界外点（50m 安全边距为底线）。交接点必须位于受控区外。
 */
final class OuterHandoffWalker {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double TARGET_CLEARANCE_METERS = 250;
    private static final double MAX_ROUTE_DISTANCE_METERS = 500;
    private static final double SAMPLE_STEP_METERS = 20;

    /**
     * @param path 从通行口交叉点沿界外道路到交接点的路径点列（含两端）
     */
    record Result(
            List<Wgs84Coordinate> path,
            String roadName,
            double routeDistanceMeters,
            double clearanceMeters) {
    }

    Optional<Result> walk(
            BaseGraph graph,
            SixthRingBoundary boundary,
            RoadClassificationIndex roadClassification,
            BooleanEncodedValue carAccess,
            ControlReleasePoint releasePoint) {
        EdgeIteratorState crossingEdge = graph.getEdgeIteratorStateForKey(releasePoint.edgeKey());
        if (crossingEdge == null) {
            return Optional.empty();
        }
        List<Wgs84Coordinate> edgeGeometry = geometry(crossingEdge);
        if (edgeGeometry.size() < 2) {
            return Optional.empty();
        }
        double[] cumulative = cumulativeDistances(edgeGeometry);
        double crossingDistance = distanceAlong(edgeGeometry, cumulative, releasePoint.coordinate());
        double edgeLength = cumulative[cumulative.length - 1];
        Wgs84Coordinate crossing = releasePoint.coordinate();
        Wgs84Coordinate baseEnd = edgeGeometry.get(0);
        Wgs84Coordinate adjEnd = edgeGeometry.get(edgeGeometry.size() - 1);
        boolean baseOutside = boundary.outsideDistanceMeters(baseEnd) > 0;
        boolean adjOutside = boundary.outsideDistanceMeters(adjEnd) > 0;
        boolean walkTowardAdj = adjOutside || !baseOutside;
        double walkableMeters = walkTowardAdj
                ? Math.max(0, edgeLength - crossingDistance)
                : Math.max(0, crossingDistance);

        Best target = new Best();
        List<Wgs84Coordinate> path = new ArrayList<>();
        path.add(crossing);
        sampleTowardOutside(
                edgeGeometry,
                cumulative,
                crossingDistance,
                walkableMeters,
                walkTowardAdj,
                boundary,
                crossingEdge.getName(),
                path,
                target);
        if (target.foundTarget()) {
            return Optional.of(target.toResult());
        }

        int startNode = walkTowardAdj ? crossingEdge.getAdjNode() : crossingEdge.getBaseNode();
        Map<Integer, Double> settledByNode = new HashMap<>();
        Deque<WalkState> queue = new ArrayDeque<>();
        queue.add(new WalkState(
                startNode,
                walkableMeters,
                path,
                crossingEdge.getName()));
        while (!queue.isEmpty()) {
            WalkState state = queue.removeFirst();
            Double settled = settledByNode.get(state.node());
            if (settled != null && settled <= state.routeDistanceMeters()) {
                continue;
            }
            settledByNode.put(state.node(), state.routeDistanceMeters());
            if (state.routeDistanceMeters() >= MAX_ROUTE_DISTANCE_METERS) {
                continue;
            }
            EdgeExplorer explorer = graph.createEdgeExplorer();
            EdgeIterator edges = explorer.setBaseNode(state.node());
            while (edges.next()) {
                if (!(edges.get(carAccess) || edges.getReverse(carAccess))) {
                    continue;
                }
                if (roadClassification.isSixthInterior(edges.getEdge())) {
                    continue;
                }
                NodeAccess nodeAccess = graph.getNodeAccess();
                int nextNode = edges.getAdjNode();
                Wgs84Coordinate nextCoordinate = new Wgs84Coordinate(
                        nodeAccess.getLon(nextNode), nodeAccess.getLat(nextNode));
                if (boundary.locate(nextCoordinate) == SixthRingBoundary.Location.INSIDE) {
                    continue;
                }
                double remaining = MAX_ROUTE_DISTANCE_METERS - state.routeDistanceMeters();
                if (remaining <= 0) {
                    continue;
                }
                PointList points = edges.fetchWayGeometry(FetchMode.ALL);
                List<Wgs84Coordinate> geometry = toCoordinates(points);
                double segmentMeters = Math.min(remaining, length(geometry));
                if (segmentMeters <= 0) {
                    continue;
                }
                List<Wgs84Coordinate> nextPath = new ArrayList<>(state.path());
                double traveled = state.routeDistanceMeters();
                double partial = 0;
                for (int index = 1; index < geometry.size(); index++) {
                    Wgs84Coordinate from = geometry.get(index - 1);
                    Wgs84Coordinate to = geometry.get(index);
                    double piece = GeoDistance.meters(from, to);
                    if (piece <= 0) {
                        continue;
                    }
                    double pieceRemaining = remaining - partial;
                    if (pieceRemaining <= 0) {
                        break;
                    }
                    double pieceToWalk = Math.min(piece, pieceRemaining);
                    int samples = (int) Math.max(1, Math.ceil(pieceToWalk / SAMPLE_STEP_METERS));
                    for (int sample = 1; sample <= samples; sample++) {
                        double fraction = (double) sample / samples;
                        Wgs84Coordinate point = interpolate(from, to, fraction);
                        if (fraction == 1) {
                            nextPath.add(to);
                        } else {
                            nextPath.add(point);
                        }
                        consider(
                                point,
                                traveled + pieceToWalk * fraction,
                                edges.getName(),
                                boundary,
                                nextPath,
                                target);
                        if (target.foundTarget()) {
                            return Optional.of(target.toResult());
                        }
                    }
                    partial += pieceToWalk;
                    traveled += pieceToWalk;
                    if (pieceToWalk < piece) {
                        break;
                    }
                }
                if (partial >= remaining) {
                    continue;
                }
                double nextDistance = state.routeDistanceMeters() + partial;
                queue.add(new WalkState(nextNode, nextDistance, nextPath, edges.getName()));
            }
        }
        return target.bestFound() ? Optional.of(target.toResult()) : Optional.empty();
    }

    private static void sampleTowardOutside(
            List<Wgs84Coordinate> geometry,
            double[] cumulative,
            double crossingDistance,
            double walkableMeters,
            boolean walkTowardAdj,
            SixthRingBoundary boundary,
            String roadName,
            List<Wgs84Coordinate> path,
            Best best) {
        double walked = 0;
        List<Integer> indexes = new ArrayList<>();
        for (int index = 1; index < geometry.size(); index++) {
            indexes.add(index);
        }
        if (!walkTowardAdj) {
            java.util.Collections.reverse(indexes);
        }
        for (int index : indexes) {
            Wgs84Coordinate from = geometry.get(index - 1);
            Wgs84Coordinate to = geometry.get(index);
            double fromOffset = cumulative[index - 1];
            double toOffset = cumulative[index];
            if (walkTowardAdj ? toOffset <= crossingDistance
                    : fromOffset >= crossingDistance) {
                continue;
            }
            double remaining = walkableMeters - walked;
            if (remaining <= 0) {
                return;
            }
            double segmentLength = toOffset - fromOffset;
            double segmentStart = walkTowardAdj
                    ? Math.max(0, crossingDistance - fromOffset)
                    : Math.min(segmentLength, crossingDistance - fromOffset);
            double pieceToWalk = walkTowardAdj
                    ? Math.min(segmentLength - segmentStart, remaining)
                    : Math.min(segmentStart, remaining);
            int samples = (int) Math.max(1, Math.ceil(pieceToWalk / SAMPLE_STEP_METERS));
            for (int sample = 1; sample <= samples; sample++) {
                double fraction = walkTowardAdj
                        ? (segmentStart + pieceToWalk * sample / samples) / segmentLength
                        : (segmentStart - pieceToWalk * sample / samples) / segmentLength;
                Wgs84Coordinate point = interpolate(from, to, fraction);
                if (sample == samples
                        && (walkTowardAdj ? fraction >= 1 : fraction <= 0)) {
                    path.add(walkTowardAdj ? to : from);
                } else {
                    path.add(point);
                }
                consider(point, walked + pieceToWalk * sample / samples, roadName, boundary, path, best);
                if (best.foundTarget()) {
                    return;
                }
            }
            walked += pieceToWalk;
            if (walkTowardAdj
                    ? pieceToWalk < segmentLength - segmentStart
                    : pieceToWalk < segmentStart) {
                return;
            }
        }
    }

    private static void consider(
            Wgs84Coordinate point,
            double routeDistanceMeters,
            String roadName,
            SixthRingBoundary boundary,
            List<Wgs84Coordinate> path,
            Best best) {
        if (routeDistanceMeters > MAX_ROUTE_DISTANCE_METERS) {
            return;
        }
        Point jtsPoint = GEOMETRY_FACTORY.createPoint(
                new Coordinate(point.lng(), point.lat()));
        Geometry controlledArea = boundary.controlledArea();
        if (controlledArea.covers(jtsPoint)) {
            return;
        }
        Coordinate nearest = DistanceOp.nearestPoints(
                controlledArea.getBoundary(), jtsPoint)[0];
        double clearance = GeoDistance.meters(
                point, new Wgs84Coordinate(nearest.x, nearest.y));
        if (clearance < 1) {
            return;
        }
        best.consider(
                point,
                roadName == null ? "" : roadName,
                routeDistanceMeters,
                clearance,
                new ArrayList<>(path));
    }

    private static List<Wgs84Coordinate> geometry(EdgeIteratorState edge) {
        return toCoordinates(edge.fetchWayGeometry(FetchMode.ALL));
    }

    private static List<Wgs84Coordinate> toCoordinates(PointList points) {
        List<Wgs84Coordinate> result = new ArrayList<>(points.size());
        for (int index = 0; index < points.size(); index++) {
            result.add(new Wgs84Coordinate(points.getLon(index), points.getLat(index)));
        }
        return result;
    }

    private static double[] cumulativeDistances(List<Wgs84Coordinate> geometry) {
        double[] cumulative = new double[geometry.size()];
        for (int index = 1; index < geometry.size(); index++) {
            cumulative[index] = cumulative[index - 1]
                    + GeoDistance.meters(geometry.get(index - 1), geometry.get(index));
        }
        return cumulative;
    }

    private static double length(List<Wgs84Coordinate> geometry) {
        double total = 0;
        for (int index = 1; index < geometry.size(); index++) {
            total += GeoDistance.meters(geometry.get(index - 1), geometry.get(index));
        }
        return total;
    }

    private static double distanceAlong(
            List<Wgs84Coordinate> geometry,
            double[] cumulative,
            Wgs84Coordinate target) {
        double bestOffset = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 1; index < geometry.size(); index++) {
            Wgs84Coordinate from = geometry.get(index - 1);
            Wgs84Coordinate to = geometry.get(index);
            double segmentLength = cumulative[index] - cumulative[index - 1];
            if (segmentLength <= 0) {
                continue;
            }
            double fraction = projectFraction(from, to, target);
            Wgs84Coordinate projected = interpolate(from, to, fraction);
            double distance = GeoDistance.meters(target, projected);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestOffset = cumulative[index - 1] + segmentLength * fraction;
            }
        }
        return bestOffset;
    }

    private static double projectFraction(
            Wgs84Coordinate from, Wgs84Coordinate to, Wgs84Coordinate target) {
        double dLng = to.lng() - from.lng();
        double dLat = to.lat() - from.lat();
        double lengthSquared = dLng * dLng + dLat * dLat;
        if (lengthSquared <= 0) {
            return 0;
        }
        double projection = ((target.lng() - from.lng()) * dLng
                + (target.lat() - from.lat()) * dLat) / lengthSquared;
        return Math.max(0, Math.min(1, projection));
    }

    private static Wgs84Coordinate interpolate(
            Wgs84Coordinate from, Wgs84Coordinate to, double fraction) {
        return new Wgs84Coordinate(
                from.lng() + (to.lng() - from.lng()) * fraction,
                from.lat() + (to.lat() - from.lat()) * fraction);
    }

    private record WalkState(
            int node,
            double routeDistanceMeters,
            List<Wgs84Coordinate> path,
            String roadName) {
    }

    private static final class Best {
        private Wgs84Coordinate targetPoint;
        private String targetRoadName;
        private double targetRouteDistance;
        private double targetClearance;
        private List<Wgs84Coordinate> targetPath;

        private Wgs84Coordinate bestPoint;
        private String bestRoadName;
        private double bestRouteDistance;
        private double bestClearance = -1;
        private List<Wgs84Coordinate> bestPath;

        void consider(
                Wgs84Coordinate point,
                String roadName,
                double routeDistance,
                double clearance,
                List<Wgs84Coordinate> path) {
            if (clearance >= TARGET_CLEARANCE_METERS
                    && (targetPoint == null || routeDistance < targetRouteDistance)) {
                targetPoint = point;
                targetRoadName = roadName;
                targetRouteDistance = routeDistance;
                targetClearance = clearance;
                targetPath = path;
            }
            if (clearance > bestClearance) {
                bestPoint = point;
                bestRoadName = roadName;
                bestRouteDistance = routeDistance;
                bestClearance = clearance;
                bestPath = path;
            }
        }

        boolean foundTarget() {
            return targetPoint != null;
        }

        boolean bestFound() {
            return bestPoint != null;
        }

        Result toResult() {
            if (targetPoint != null) {
                return new Result(targetPath, targetRoadName, targetRouteDistance, targetClearance);
            }
            return new Result(bestPath, bestRoadName, bestRouteDistance, bestClearance);
        }
    }
}
