package cn.camera.safe.routing;

import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Chooses a user-operable reference-road point outside the controlled area. */
final class NavigationHandoffSelector {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double TARGET_CLEARANCE_METERS = 250;
    private static final double MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS = 500;
    private static final double HIGHWAY_TARGET_ROUTE_DISTANCE_METERS = 300;
    private static final Set<RoadClass> RELIABLE_ROAD_CLASSES = Set.of(
            RoadClass.PRIMARY,
            RoadClass.SECONDARY,
            RoadClass.TERTIARY,
            RoadClass.RESIDENTIAL,
            RoadClass.UNCLASSIFIED,
            RoadClass.SERVICE,
            RoadClass.LIVING_STREET);

    Optional<NavigationHandoffPoint> select(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            SixthRingPortal physicalCrossing,
            RoadClassificationIndex roadClassification,
            PortalAnchoredRoute anchoredRoute) {
        Optional<NavigationHandoffPoint> ordinary = selectReferenceRoad(
                direction, boundary, physicalCrossing, anchoredRoute);
        if (ordinary.isPresent() || direction != SixthRingPortal.Direction.INBOUND) {
            return ordinary;
        }
        return selectInboundHighway(
                boundary, physicalCrossing, roadClassification, anchoredRoute);
    }

    Optional<NavigationHandoffPoint> select(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            PortalAnchoredRoute anchoredRoute) {
        Optional<NavigationHandoffPoint> ordinary = selectReferenceRoad(
                direction, boundary, null, anchoredRoute);
        if (ordinary.isPresent() || direction != SixthRingPortal.Direction.INBOUND) {
            return ordinary;
        }
        return selectInboundHighway(boundary, null, null, anchoredRoute);
    }

    private Optional<NavigationHandoffPoint> selectReferenceRoad(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            SixthRingPortal physicalCrossing,
            PortalAnchoredRoute anchoredRoute) {
        List<cn.camera.safe.coordinate.Wgs84Coordinate> geometry =
                anchoredRoute.route().geometry();
        if (geometry.size() < 3) {
            return Optional.empty();
        }

        double[] distanceFromStart = cumulativeDistances(geometry);
        double totalDistance = distanceFromStart[distanceFromStart.length - 1];
        double crossingDistance = physicalCrossing == null
                ? (direction == SixthRingPortal.Direction.OUTBOUND ? 0 : totalDistance)
                : crossingDistance(direction, physicalCrossing, geometry, distanceFromStart);
        Geometry outer = boundary.controlledArea();
        List<Candidate> candidates = new ArrayList<>();
        for (RouteTracePoint tracePoint : anchoredRoute.trace()) {
            int index = tracePoint.geometryIndex();
            if (index <= 0 || index >= geometry.size() - 1 || !isNavigableRoad(tracePoint)) {
                continue;
            }
            double distanceFromBoundary = direction == SixthRingPortal.Direction.OUTBOUND
                    ? distanceFromStart[index] - crossingDistance
                    : crossingDistance - distanceFromStart[index];
            if (distanceFromBoundary <= 0
                    || distanceFromBoundary > MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS) {
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
                NavigationHandoffPoint.Type.ORDINARY_ROAD,
                NavigationHandoffPoint.Segment.REFERENCE,
                tracePoint.geometryIndex(),
                1));
    }

    private Optional<NavigationHandoffPoint> selectInboundHighway(
            SixthRingBoundary boundary,
            SixthRingPortal physicalCrossing,
            RoadClassificationIndex roadClassification,
            PortalAnchoredRoute anchoredRoute) {
        List<cn.camera.safe.coordinate.Wgs84Coordinate> geometry =
                anchoredRoute.route().geometry();
        if (geometry.size() < 2) {
            return Optional.empty();
        }
        double[] distanceFromStart = cumulativeDistances(geometry);
        double totalDistance = distanceFromStart[distanceFromStart.length - 1];
        double crossingDistance = physicalCrossing == null
                ? totalDistance
                : crossingDistance(
                        SixthRingPortal.Direction.INBOUND,
                        physicalCrossing,
                        geometry,
                        distanceFromStart);
        Geometry controlledArea = boundary.controlledArea();
        List<Candidate> candidates = new ArrayList<>();
        for (int geometryIndex = 1; geometryIndex < geometry.size(); geometryIndex++) {
            RouteTracePoint motorwayTrace = highwayTraceForSegment(
                    anchoredRoute.trace(), geometryIndex, roadClassification).orElse(null);
            if (motorwayTrace == null) {
                continue;
            }
            double segmentMeters = distanceFromStart[geometryIndex]
                    - distanceFromStart[geometryIndex - 1];
            if (segmentMeters <= 0) {
                continue;
            }
            double nearBoundaryMeters = crossingDistance - distanceFromStart[geometryIndex];
            double farBoundaryMeters = crossingDistance - distanceFromStart[geometryIndex - 1];
            if (nearBoundaryMeters > MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS
                    || farBoundaryMeters <= 0) {
                continue;
            }
            double targetMeters = Math.max(
                    nearBoundaryMeters,
                    Math.min(HIGHWAY_TARGET_ROUTE_DISTANCE_METERS,
                            Math.min(farBoundaryMeters, MAX_ROUTE_DISTANCE_FROM_BOUNDARY_METERS)));
            double targetFromStart = crossingDistance - targetMeters;
            double fraction = (targetFromStart - distanceFromStart[geometryIndex - 1])
                    / segmentMeters;
            fraction = Math.max(0, Math.min(1, fraction));
            if (fraction <= 0) {
                continue;
            }
            cn.camera.safe.coordinate.Wgs84Coordinate coordinate = interpolate(
                    geometry.get(geometryIndex - 1), geometry.get(geometryIndex), fraction);
            Point point = GEOMETRY_FACTORY.createPoint(
                    new Coordinate(coordinate.lng(), coordinate.lat()));
            if (controlledArea.covers(point)) {
                continue;
            }
            double clearance = boundary.outsideDistanceMeters(coordinate);
            candidates.add(new Candidate(
                    motorwayTrace,
                    targetMeters,
                    clearance,
                    coordinate,
                    geometryIndex,
                    fraction));
        }
        return candidates.stream()
                .min(Comparator.<Candidate>comparingDouble(candidate -> Math.abs(
                                candidate.distanceFromBoundaryMeters()
                                        - HIGHWAY_TARGET_ROUTE_DISTANCE_METERS))
                        .thenComparingDouble(Candidate::distanceFromBoundaryMeters)
                        .thenComparingInt(Candidate::geometryIndex))
                .map(candidate -> new NavigationHandoffPoint(
                        candidate.coordinate(),
                        candidate.clearanceMeters(),
                        candidate.tracePoint().roadName(),
                        NavigationHandoffPoint.Type.HIGHWAY,
                        NavigationHandoffPoint.Segment.REFERENCE,
                        candidate.geometryIndex(),
                        candidate.fractionFromPrevious()));
    }

    private static Optional<RouteTracePoint> highwayTraceForSegment(
            List<RouteTracePoint> trace,
            int geometryIndex,
            RoadClassificationIndex roadClassification) {
        List<RouteTracePoint> atPrevious = trace.stream()
                .filter(point -> point.geometryIndex() == geometryIndex - 1)
                .filter(point -> isHighwayMainline(point, roadClassification))
                .toList();
        return trace.stream()
                .filter(point -> point.geometryIndex() == geometryIndex)
                .filter(point -> isHighwayMainline(point, roadClassification))
                .filter(current -> atPrevious.stream().anyMatch(previous ->
                        previous.roadClass() == current.roadClass()
                                && previous.roadClassLink() == current.roadClassLink()
                                && previous.roadEnvironment() == current.roadEnvironment()
                                && previous.roadName().equals(current.roadName())))
                .findFirst();
    }

    private static boolean isHighwayMainline(
            RouteTracePoint point,
            RoadClassificationIndex roadClassification) {
        if (point.roadEnvironment() == RoadEnvironment.TUNNEL
                || point.roadEnvironment() == RoadEnvironment.FERRY) {
            return false;
        }
        if (roadClassification != null && point.baseEdgeId() >= 0) {
            return roadClassification.isAnyHighwayMainline(point.baseEdgeId());
        }
        return point.roadClass() == RoadClass.MOTORWAY
                && !point.roadClassLink()
                && point.roadEnvironment() != RoadEnvironment.TUNNEL
                && point.roadEnvironment() != RoadEnvironment.FERRY;
    }

    private static cn.camera.safe.coordinate.Wgs84Coordinate interpolate(
            cn.camera.safe.coordinate.Wgs84Coordinate from,
            cn.camera.safe.coordinate.Wgs84Coordinate to,
            double fraction) {
        if (fraction >= 1) {
            return to;
        }
        return new cn.camera.safe.coordinate.Wgs84Coordinate(
                from.lng() + (to.lng() - from.lng()) * fraction,
                from.lat() + (to.lat() - from.lat()) * fraction);
    }

    private static boolean isNavigableRoad(RouteTracePoint point) {
        return RELIABLE_ROAD_CLASSES.contains(point.roadClass())
                && !point.roadClassLink()
                && point.roadEnvironment() != RoadEnvironment.TUNNEL
                && point.roadEnvironment() != RoadEnvironment.FERRY;
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

    private static double crossingDistance(
            SixthRingPortal.Direction direction,
            SixthRingPortal physicalCrossing,
            List<cn.camera.safe.coordinate.Wgs84Coordinate> geometry,
            double[] cumulativeDistances) {
        int selectedIndex = -1;
        for (int index = 0; index < geometry.size(); index++) {
            if (!geometry.get(index).equals(physicalCrossing.crossing())) {
                continue;
            }
            selectedIndex = index;
            if (direction == SixthRingPortal.Direction.INBOUND) {
                break;
            }
        }
        if (selectedIndex < 0) {
            throw new IllegalStateException(
                    "physical boundary crossing is missing from the anchored route geometry");
        }
        return cumulativeDistances[selectedIndex];
    }

    private record Candidate(
            RouteTracePoint tracePoint,
            double distanceFromBoundaryMeters,
            double clearanceMeters,
            cn.camera.safe.coordinate.Wgs84Coordinate coordinate,
            int geometryIndex,
            double fractionFromPrevious) {

        private Candidate(
                RouteTracePoint tracePoint,
                double distanceFromBoundaryMeters,
                double clearanceMeters) {
            this(tracePoint, distanceFromBoundaryMeters, clearanceMeters,
                    tracePoint.coordinate(), tracePoint.geometryIndex(), 1);
        }
    }
}
