package cn.camera.safe.routing;

import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Chooses a user-operable road point without exposing a motorway mainline as a navigation endpoint. */
final class NavigationHandoffSelector {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double TARGET_CLEARANCE_METERS = 250;
    private static final double MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS = 1_000;

    Optional<NavigationHandoffPoint> select(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            TracedRouteLeg safeRoute,
            PortalAnchoredRoute anchoredRoute) {
        if (direction == SixthRingPortal.Direction.INBOUND
                && inboundBoundaryIsHighway(safeRoute, anchoredRoute)) {
            return selectInboundHighwayExit(boundary, safeRoute);
        }
        return selectReferenceRoad(direction, boundary, anchoredRoute);
    }

    private Optional<NavigationHandoffPoint> selectReferenceRoad(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            PortalAnchoredRoute anchoredRoute) {
        List<cn.camera.safe.coordinate.Wgs84Coordinate> geometry =
                anchoredRoute.route().geometry();
        if (geometry.size() < 3) {
            return Optional.empty();
        }

        double[] distanceFromStart = cumulativeDistances(geometry);
        double totalDistance = distanceFromStart[distanceFromStart.length - 1];
        Polygon outer = boundary.outerPolygon();
        List<Candidate> candidates = new ArrayList<>();
        for (RouteTracePoint tracePoint : anchoredRoute.trace()) {
            int index = tracePoint.geometryIndex();
            if (index <= 0 || index >= geometry.size() - 1 || !isNavigableRoad(tracePoint)) {
                continue;
            }
            double distanceFromBoundary = direction == SixthRingPortal.Direction.OUTBOUND
                    ? distanceFromStart[index]
                    : totalDistance - distanceFromStart[index];
            if (distanceFromBoundary > MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS) {
                continue;
            }
            Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(
                    tracePoint.coordinate().lng(), tracePoint.coordinate().lat()));
            if (outer.covers(point)) {
                continue;
            }
            Coordinate nearest = DistanceOp.nearestPoints(outer.getBoundary(), point)[0];
            double clearance = GeoDistance.meters(
                    tracePoint.coordinate(),
                    new cn.camera.safe.coordinate.Wgs84Coordinate(nearest.x, nearest.y));
            candidates.add(new Candidate(tracePoint, distanceFromBoundary, clearance));
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Comparator<Candidate> nearestAlongRoute = Comparator
                .comparingDouble(Candidate::distanceFromBoundaryMeters)
                .thenComparingInt(candidate -> candidate.tracePoint().geometryIndex());
        Candidate selected = candidates.stream()
                .filter(candidate -> candidate.clearanceMeters() >= TARGET_CLEARANCE_METERS)
                .min(nearestAlongRoute)
                .orElseGet(() -> candidates.stream()
                        .max(Comparator.comparingDouble(Candidate::clearanceMeters)
                                .thenComparing(nearestAlongRoute.reversed()))
                        .orElseThrow());
        RouteTracePoint tracePoint = selected.tracePoint();
        return Optional.of(new NavigationHandoffPoint(
                tracePoint.coordinate(),
                selected.clearanceMeters(),
                tracePoint.roadName(),
                NavigationHandoffPoint.Segment.REFERENCE,
                tracePoint.geometryIndex()));
    }

    private Optional<NavigationHandoffPoint> selectInboundHighwayExit(
            SixthRingBoundary boundary,
            TracedRouteLeg safeRoute) {
        List<cn.camera.safe.coordinate.Wgs84Coordinate> geometry = safeRoute.leg().geometry();
        if (geometry.size() < 3) {
            return Optional.empty();
        }
        double[] distanceFromBoundary = cumulativeDistances(geometry);
        int lastRestrictedIndex = -1;
        for (RouteTracePoint tracePoint : safeRoute.trace()) {
            int index = tracePoint.geometryIndex();
            if (index < 0 || index >= geometry.size()
                    || distanceFromBoundary[index] > MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS) {
                continue;
            }
            if (isHighwayOrLink(tracePoint)) {
                lastRestrictedIndex = Math.max(lastRestrictedIndex, index);
                continue;
            }
            if (lastRestrictedIndex < 0
                    || index <= lastRestrictedIndex
                    || index >= geometry.size() - 1
                    || !isNavigableRoad(tracePoint)) {
                continue;
            }
            double clearance = boundaryClearanceMeters(boundary.outerPolygon(), tracePoint);
            if (clearance <= 0) {
                continue;
            }
            return Optional.of(new NavigationHandoffPoint(
                    tracePoint.coordinate(),
                    clearance,
                    tracePoint.roadName(),
                    NavigationHandoffPoint.Segment.SAFE,
                    index));
        }
        return Optional.empty();
    }

    private static boolean inboundBoundaryIsHighway(
            TracedRouteLeg safeRoute,
            PortalAnchoredRoute anchoredRoute) {
        int referenceBoundaryIndex = anchoredRoute.route().geometry().size() - 1;
        return anchoredRoute.trace().stream()
                        .filter(point -> point.geometryIndex() == referenceBoundaryIndex)
                        .anyMatch(NavigationHandoffSelector::isHighwayOrLink)
                || safeRoute.trace().stream()
                        .filter(point -> point.geometryIndex() == 0)
                        .anyMatch(NavigationHandoffSelector::isHighwayOrLink);
    }

    private static boolean isNavigableRoad(RouteTracePoint point) {
        return point.roadClass() != RoadClass.MOTORWAY
                && !point.roadClassLink()
                && point.roadEnvironment() != RoadEnvironment.TUNNEL
                && point.roadEnvironment() != RoadEnvironment.FERRY;
    }

    private static boolean isHighwayOrLink(RouteTracePoint point) {
        return point.roadClass() == RoadClass.MOTORWAY || point.roadClassLink();
    }

    private static double boundaryClearanceMeters(
            Polygon outer,
            RouteTracePoint tracePoint) {
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(
                tracePoint.coordinate().lng(), tracePoint.coordinate().lat()));
        Coordinate nearest = DistanceOp.nearestPoints(outer.getBoundary(), point)[0];
        return GeoDistance.meters(
                tracePoint.coordinate(),
                new cn.camera.safe.coordinate.Wgs84Coordinate(nearest.x, nearest.y));
    }

    private static double[] cumulativeDistances(
            List<cn.camera.safe.coordinate.Wgs84Coordinate> geometry) {
        double[] distances = new double[geometry.size()];
        for (int index = 1; index < geometry.size(); index++) {
            distances[index] = distances[index - 1]
                    + GeoDistance.meters(geometry.get(index - 1), geometry.get(index));
        }
        return distances;
    }

    private record Candidate(
            RouteTracePoint tracePoint,
            double distanceFromBoundaryMeters,
            double clearanceMeters) {
    }
}
