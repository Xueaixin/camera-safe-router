package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import com.graphhopper.util.GHUtility;

/** Independent final check for the mode-specific sixth-ring transition shape. */
public final class SixthRingRouteShapeValidator {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double BOUNDARY_TOLERANCE_DEGREES = 0.00001;

    public boolean isValid(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            RouteLeg safeSegment,
            RouteLeg referenceSegment) {
        Geometry safeArea = boundary.controlledArea();
        Geometry bufferedSafeArea = safeArea.buffer(BOUNDARY_TOLERANCE_DEGREES);
        Geometry restrictedInterior = safeArea.buffer(-BOUNDARY_TOLERANCE_DEGREES);
        if (restrictedInterior.isEmpty()) {
            restrictedInterior = safeArea;
        }
        return bufferedSafeArea.covers(lineString(safeSegment.geometry()))
                && !restrictedInterior.intersects(lineString(referenceSegment.geometry()));
    }

    public boolean isValid(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            List<RouteTracePoint> actualDirectionTrace,
            RoadClassificationIndex roadClassification) {
        return isValid(
                direction,
                boundary,
                actualDirectionTrace,
                roadClassification,
                TollCorridorTopology.empty(0),
                HighwayInterchangeTopology.empty());
    }

    public boolean isValid(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            List<RouteTracePoint> actualDirectionTrace,
            RoadClassificationIndex roadClassification,
            TollCorridorTopology tollCorridors) {
        return isValid(
                direction,
                boundary,
                actualDirectionTrace,
                roadClassification,
                tollCorridors,
                HighwayInterchangeTopology.empty());
    }

    public boolean isValid(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            List<RouteTracePoint> actualDirectionTrace,
            RoadClassificationIndex roadClassification,
            TollCorridorTopology tollCorridors,
            HighwayInterchangeTopology interchangeTopology) {
        List<RouteTraceSupport.EdgeRun> actualRuns = new ArrayList<>(
                RouteTraceSupport.edgeRuns(actualDirectionTrace));
        if (actualRuns.isEmpty()) {
            return false;
        }
        boolean[] corridorRun = matchCorridorBlocks(
                actualRuns, tollCorridors, interchangeTopology);

        List<RouteTraceSupport.EdgeRun> runs = new ArrayList<>(
                actualRuns);
        if (direction == SixthRingPortal.Direction.INBOUND) {
            Collections.reverse(runs);
            runs = runs.stream().map(SixthRingRouteShapeValidator::reverse).toList();
            boolean[] reversedCorridorRun = new boolean[runs.size()];
            for (int index = 0; index < actualRuns.size(); index++) {
                reversedCorridorRun[runs.size() - 1 - index] = corridorRun[index];
            }
            corridorRun = reversedCorridorRun;
        }
        return shapeIsValid(
                runs, boundary, roadClassification, tollCorridors, interchangeTopology, corridorRun);
    }

    private static boolean shapeIsValid(
            List<RouteTraceSupport.EdgeRun> runs,
            SixthRingBoundary boundary,
            RoadClassificationIndex roadClassification,
            TollCorridorTopology tollCorridors,
            HighwayInterchangeTopology interchangeTopology,
            boolean[] corridorRun) {
        Geometry controlledArea = boundary.controlledArea();
        Geometry buffered = controlledArea.buffer(BOUNDARY_TOLERANCE_DEGREES);
        Geometry restrictedInterior = controlledArea.buffer(-BOUNDARY_TOLERANCE_DEGREES);
        if (restrictedInterior.isEmpty()) {
            restrictedInterior = controlledArea;
        }
        boolean hasCorridors = !tollCorridors.corridors().isEmpty()
                || !interchangeTopology.corridors().isEmpty();
        State state = State.CONTROLLED;
        for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
            RouteTraceSupport.EdgeRun run = runs.get(runIndex);
            if (corridorRun[runIndex]) {
                if (state != State.RELEASED) {
                    return false;
                }
                continue;
            }
            int baseEdgeId = run.baseEdgeId();
            if (roadClassification.isForbiddenSixthInteriorHighway(baseEdgeId)) {
                return false;
            }
            if (roadClassification.isAnyHighwayMainline(baseEdgeId)) {
                if (state == State.CONTROLLED) {
                    return false;
                }
                continue;
            }
        if (roadClassification.isMotorwayLink(baseEdgeId)) {
            if (state == State.CONTROLLED) {
                return false;
            }
            continue;
        }
            List<Wgs84Coordinate> geometry = run.geometry();
            for (int index = 1; index < geometry.size(); index++) {
                LineString segment = lineString(List.of(
                        geometry.get(index - 1), geometry.get(index)));
                if (state == State.RELEASED) {
                    if (roadClassification.isTongzhouCheckpointBypass(baseEdgeId)) {
                        continue;
                    }
                    if (restrictedInterior.intersects(segment)) {
                        return false;
                    }
                    continue;
                }
                if (buffered.covers(segment)) {
                    continue;
                }
                if (!buffered.covers(GEOMETRY_FACTORY.createPoint(new Coordinate(
                        geometry.get(index - 1).lng(), geometry.get(index - 1).lat())))
                        || restrictedInterior.covers(GEOMETRY_FACTORY.createPoint(new Coordinate(
                        geometry.get(index).lng(), geometry.get(index).lat())))) {
                    return false;
                }
                state = State.RELEASED;
            }
        }
        Wgs84Coordinate canonicalEnd = runs.getLast().geometry().getLast();
        return state == State.RELEASED
                && linkChainsAreValid(
                runs, roadClassification, restrictedInterior, corridorRun, hasCorridors)
                && !buffered.covers(GEOMETRY_FACTORY.createPoint(new Coordinate(
                        canonicalEnd.lng(), canonicalEnd.lat())));
    }

    private static boolean[] matchCorridorBlocks(
            List<RouteTraceSupport.EdgeRun> runs,
            TollCorridorTopology tollCorridors,
            HighwayInterchangeTopology interchangeTopology) {
        boolean[] corridorRun = new boolean[runs.size()];
        for (TollCorridorTopology.TollCorridor corridor
                : tollCorridors.corridors()) {
            List<Integer> keys = corridor.directedEdgeKeys();
            if (keys.isEmpty()) {
                continue;
            }
            for (int start = 0; start + keys.size() <= runs.size(); start++) {
                if (!keysMatch(runs, start, keys)) {
                    continue;
                }
                int corridorEnd = start + keys.size();
                boolean mainlineAdjacent = corridor.role() == TollCorridorTopology.Role.ENTRY
                        ? corridorEnd < runs.size()
                        && runs.get(corridorEnd).originalEdgeKey()
                        == corridor.sixthRingMainlineEdgeKey()
                        : start > 0
                        && runs.get(start - 1).originalEdgeKey()
                        == corridor.sixthRingMainlineEdgeKey();
                if (!mainlineAdjacent) {
                    continue;
                }
                for (int index = start; index < corridorEnd; index++) {
                    corridorRun[index] = true;
                }
            }
        }
        for (HighwayInterchangeTopology.InterchangeCorridor corridor
                : interchangeTopology.corridors()) {
            List<Integer> keys = corridor.directedEdgeKeys();
            if (keys.isEmpty()) {
                continue;
            }
            for (int start = 0; start + keys.size() <= runs.size(); start++) {
                if (!keysMatch(runs, start, keys)) {
                    continue;
                }
                int corridorEnd = start + keys.size();
                boolean mainlineAdjacent = switch (corridor.role()) {
                    case R_TO_HT -> start > 0
                            && runs.get(start - 1).originalEdgeKey()
                            == corridor.rMainlineEdgeKey()
                            && corridorEnd < runs.size()
                            && runs.get(corridorEnd).originalEdgeKey()
                            == corridor.htMainlineEdgeKey();
                    case HT_TO_R -> start > 0
                            && runs.get(start - 1).originalEdgeKey()
                            == corridor.htMainlineEdgeKey()
                            && corridorEnd < runs.size()
                            && runs.get(corridorEnd).originalEdgeKey()
                            == corridor.rMainlineEdgeKey();
                };
                if (!mainlineAdjacent) {
                    continue;
                }
                for (int index = start; index < corridorEnd; index++) {
                    corridorRun[index] = true;
                }
            }
        }
        return corridorRun;
    }

    private static boolean keysMatch(
            List<RouteTraceSupport.EdgeRun> runs,
            int start,
            List<Integer> keys) {
        for (int offset = 0; offset < keys.size(); offset++) {
            if (runs.get(start + offset).originalEdgeKey() != keys.get(offset)) {
                return false;
            }
        }
        return true;
    }

    private static boolean linkChainsAreValid(
            List<RouteTraceSupport.EdgeRun> runs,
            RoadClassificationIndex roadClassification,
            Geometry restrictedInterior,
            boolean[] corridorRun,
            boolean hasCorridors) {
        int index = 0;
        while (index < runs.size()) {
            RouteTraceSupport.EdgeRun run = runs.get(index);
            if (corridorRun[index]
                    || !roadClassification.isMotorwayLink(run.baseEdgeId())) {
                index++;
                continue;
            }
            int start = index;
            boolean containsSixth = false;
            boolean containsTongzhou = false;
            boolean intersectsInterior = false;
            while (index < runs.size()
                    && !corridorRun[index]
                    && roadClassification.isMotorwayLink(runs.get(index).baseEdgeId())) {
                RouteTraceSupport.EdgeRun link = runs.get(index);
                if (roadClassification.isTongzhouOutsideSixth(link.baseEdgeId())
                        && !roadClassification.isTongzhouHighwayConnector(link.baseEdgeId())) {
                    return false;
                }
                containsSixth |= roadClassification.isSixthInterior(link.baseEdgeId());
                containsTongzhou |= roadClassification.isTongzhouOutsideSixth(
                        link.baseEdgeId());
                intersectsInterior |= intersectsInterior(link, restrictedInterior);
                index++;
            }
            RouteTraceSupport.EdgeRun previous = start > 0 ? runs.get(start - 1) : null;
            RouteTraceSupport.EdgeRun next = index < runs.size() ? runs.get(index) : null;
            boolean previousMainline = previous != null
                    && roadClassification.isAnyHighwayMainline(previous.baseEdgeId());
            boolean nextMainline = next != null
                    && roadClassification.isAnyHighwayMainline(next.baseEdgeId());
            boolean touchesSixthRing = (previous != null
                    && roadClassification.isSixthRingMainline(previous.baseEdgeId()))
                    || (next != null
                    && roadClassification.isSixthRingMainline(next.baseEdgeId()));
            if (hasCorridors) {
                if (containsSixth) {
                    return false;
                }
                if (touchesSixthRing && !(previousMainline && nextMainline)) {
                    return false;
                }
                if (containsTongzhou && !(previousMainline && nextMainline)) {
                    return false;
                }
                if (intersectsInterior && !(previousMainline && nextMainline)) {
                    return false;
                }
                continue;
            }
            if (containsSixth) {
                for (int linkIndex = start; linkIndex < index; linkIndex++) {
                    RouteTraceSupport.EdgeRun link = runs.get(linkIndex);
                    if (roadClassification.isSixthInterior(link.baseEdgeId())
                            && !roadClassification.isSixthExitConnectorEdgeKey(
                            link.originalEdgeKey())) {
                        return false;
                    }
                }
                if (previous == null
                        || !roadClassification.isSixthRingMainline(previous.baseEdgeId())
                        || next == null
                        || intersectsInterior(next, restrictedInterior)) {
                    return false;
                }
            } else if (containsTongzhou) {
                if (previous == null || next == null
                        || !roadClassification.isHighwayMainline(previous.baseEdgeId())
                        || !roadClassification.isHighwayMainline(next.baseEdgeId())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean intersectsInterior(
            RouteTraceSupport.EdgeRun run,
            Geometry restrictedInterior) {
        List<Wgs84Coordinate> geometry = run.geometry();
        for (int index = 1; index < geometry.size(); index++) {
            if (restrictedInterior.intersects(lineString(List.of(
                    geometry.get(index - 1), geometry.get(index))))) {
                return true;
            }
        }
        return false;
    }

    private static RouteTraceSupport.EdgeRun reverse(RouteTraceSupport.EdgeRun run) {
        List<Wgs84Coordinate> geometry = new ArrayList<>(run.geometry());
        Collections.reverse(geometry);
        return new RouteTraceSupport.EdgeRun(
                GHUtility.reverseEdgeKey(run.originalEdgeKey()), geometry);
    }

    private static LineString lineString(List<Wgs84Coordinate> geometry) {
        Coordinate[] coordinates = geometry.stream()
                .map(point -> new Coordinate(point.lng(), point.lat()))
                .toArray(Coordinate[]::new);
        return GEOMETRY_FACTORY.createLineString(coordinates);
    }

    private enum State {
        CONTROLLED,
        RELEASED
    }
}
