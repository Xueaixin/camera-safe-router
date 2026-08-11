package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.List;
import java.util.Objects;

public record PlannedRoute(
        RoutePlanningMode planningMode,
        String boundaryVersion,
        SixthRingPortal.Direction boundaryDirection,
        SixthRingPortal boundaryCrossing,
        NavigationHandoffPoint navigationHandoff,
        RouteLeg safeSegment,
        RouteLeg referenceSegment,
        ExternalHandoffPoint externalHandoff,
        double distanceMeters,
        long durationMillis,
        List<Wgs84Coordinate> geometry,
        List<RouteTracePoint> trace,
        long searchEdgeChecks,
        long virtualEdgeChecks,
        long blockedRejections) {

    public PlannedRoute {
        Objects.requireNonNull(planningMode, "planningMode");
        if (boundaryVersion == null || boundaryVersion.isBlank()) {
            throw new IllegalArgumentException("boundary version is required");
        }
        geometry = List.copyOf(geometry);
        trace = List.copyOf(trace);
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("route distance and duration must be non-negative");
        }
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("planned route geometry requires at least two points");
        }
        validateMode(
                planningMode,
                boundaryDirection,
                boundaryCrossing,
                navigationHandoff,
                safeSegment,
                referenceSegment,
                externalHandoff);
    }

    public PlannedRoute(
            RoutePlanningMode planningMode,
            String boundaryVersion,
            SixthRingPortal.Direction boundaryDirection,
            SixthRingPortal boundaryCrossing,
            NavigationHandoffPoint navigationHandoff,
            RouteLeg safeSegment,
            RouteLeg referenceSegment,
            ExternalHandoffPoint externalHandoff,
            double distanceMeters,
            long durationMillis,
            List<Wgs84Coordinate> geometry,
            long searchEdgeChecks,
            long virtualEdgeChecks,
            long blockedRejections) {
        this(planningMode, boundaryVersion, boundaryDirection, boundaryCrossing,
                navigationHandoff, safeSegment, referenceSegment, externalHandoff,
                distanceMeters, durationMillis, geometry, List.of(), searchEdgeChecks,
                virtualEdgeChecks, blockedRejections);
    }

    private static void validateMode(
            RoutePlanningMode mode,
            SixthRingPortal.Direction direction,
            SixthRingPortal crossing,
            NavigationHandoffPoint navigationHandoff,
            RouteLeg safe,
            RouteLeg reference,
            ExternalHandoffPoint externalHandoff) {
        switch (mode) {
            case INTERNAL_SAFE -> {
                if (direction != null || crossing != null || navigationHandoff != null || safe == null
                        || reference != null || externalHandoff != null) {
                    throw new IllegalArgumentException("invalid INTERNAL_SAFE route shape");
                }
            }
            case CROSS_BOUNDARY_OUTBOUND -> validateCrossBoundary(
                    SixthRingPortal.Direction.OUTBOUND,
                    SixthRingPortal.BoundaryRole.OUTER_EXIT,
                    direction, crossing, navigationHandoff, safe, reference, externalHandoff);
            case CROSS_BOUNDARY_INBOUND -> validateCrossBoundary(
                    SixthRingPortal.Direction.INBOUND,
                    SixthRingPortal.BoundaryRole.INNER_ENTRY,
                    direction, crossing, navigationHandoff, safe, reference, externalHandoff);
            case EXTERNAL_ONLY -> {
                if (direction != null || crossing != null || navigationHandoff != null || safe != null
                        || reference == null || externalHandoff != null) {
                    throw new IllegalArgumentException("invalid EXTERNAL_ONLY route shape");
                }
            }
        }
    }

    private static void validateCrossBoundary(
            SixthRingPortal.Direction expectedDirection,
            SixthRingPortal.BoundaryRole expectedRole,
            SixthRingPortal.Direction direction,
            SixthRingPortal crossing,
            NavigationHandoffPoint navigationHandoff,
            RouteLeg safe,
            RouteLeg reference,
            ExternalHandoffPoint externalHandoff) {
        if (direction != expectedDirection || crossing == null
                || crossing.direction() != expectedDirection
                || crossing.boundaryRole() != expectedRole
                || safe == null || reference == null
                || (navigationHandoff == null) != (externalHandoff == null)) {
            throw new IllegalArgumentException("invalid cross-boundary route shape");
        }
        if (navigationHandoff != null) {
            Wgs84Coordinate safeJoin = expectedDirection == SixthRingPortal.Direction.OUTBOUND
                    ? safe.geometry().getLast()
                    : safe.geometry().getFirst();
            Wgs84Coordinate referenceJoin = expectedDirection == SixthRingPortal.Direction.OUTBOUND
                    ? reference.geometry().getFirst()
                    : reference.geometry().getLast();
            if (!navigationHandoff.coordinate().equals(safeJoin)
                    || !navigationHandoff.coordinate().equals(referenceJoin)) {
                throw new IllegalArgumentException(
                        "navigation handoff must equal the safe/reference segment join");
            }
        }
    }
}
