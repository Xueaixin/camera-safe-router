package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.ArrayList;
import java.util.List;

public final class RouteTraceSupport {
    private RouteTraceSupport() {
    }

    static List<RouteTracePoint> join(
            List<RouteTracePoint> first,
            int firstGeometrySize,
            List<RouteTracePoint> second) {
        List<RouteTracePoint> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        int offset = Math.max(0, firstGeometrySize - 1);
        second.stream()
                .map(point -> withGeometryIndex(point, point.geometryIndex() + offset))
                .forEach(result::add);
        return List.copyOf(result);
    }

    static RouteTracePoint withGeometryIndex(RouteTracePoint point, int geometryIndex) {
        return new RouteTracePoint(
                geometryIndex,
                point.coordinate(),
                point.roadName(),
                point.roadClass(),
                point.roadClassLink(),
                point.roadEnvironment(),
                point.originalEdgeKey());
    }

    public static List<EdgeRun> edgeRuns(List<RouteTracePoint> trace) {
        List<EdgeRun> runs = new ArrayList<>();
        int currentKey = Integer.MIN_VALUE;
        List<Wgs84Coordinate> coordinates = new ArrayList<>();
        for (RouteTracePoint point : trace) {
            if (point.originalEdgeKey() < 0) {
                continue;
            }
            if (point.originalEdgeKey() != currentKey) {
                appendRun(runs, currentKey, coordinates);
                currentKey = point.originalEdgeKey();
                coordinates = new ArrayList<>();
            }
            if (coordinates.isEmpty() || !coordinates.getLast().equals(point.coordinate())) {
                coordinates.add(point.coordinate());
            }
        }
        appendRun(runs, currentKey, coordinates);
        return List.copyOf(runs);
    }

    private static void appendRun(
            List<EdgeRun> runs,
            int edgeKey,
            List<Wgs84Coordinate> coordinates) {
        if (edgeKey >= 0 && !coordinates.isEmpty()) {
            runs.add(new EdgeRun(edgeKey, coordinates));
        }
    }

    public record EdgeRun(
            int originalEdgeKey,
            List<Wgs84Coordinate> geometry) {
        public EdgeRun {
            if (originalEdgeKey < 0) {
                throw new IllegalArgumentException("edge run key must be non-negative");
            }
            geometry = List.copyOf(geometry);
        }

        public int baseEdgeId() {
            return originalEdgeKey / 2;
        }
    }
}
