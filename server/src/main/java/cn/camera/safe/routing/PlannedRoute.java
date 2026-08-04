package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.List;
import java.util.Objects;

public record PlannedRoute(
        RoutePlanningMode planningMode,
        String boundaryVersion,
        SixthRingPortal.Direction boundaryDirection,
        SixthRingPortal boundaryCrossing,
        RouteLeg safeSegment,
        RouteLeg referenceSegment,
        double distanceMeters,
        long durationMillis,
        List<Wgs84Coordinate> geometry,
        long searchEdgeChecks,
        long virtualEdgeChecks,
        long blockedRejections) {

    public PlannedRoute {
        Objects.requireNonNull(planningMode, "planningMode");
        if (boundaryVersion == null || boundaryVersion.isBlank()) {
            throw new IllegalArgumentException("boundary version is required");
        }
        geometry = List.copyOf(geometry);
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("route distance and duration must be non-negative");
        }
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("planned route geometry requires at least two points");
        }
        validateMode(planningMode, boundaryDirection, boundaryCrossing, safeSegment, referenceSegment);
    }

    private static void validateMode(
            RoutePlanningMode mode,
            SixthRingPortal.Direction direction,
            SixthRingPortal crossing,
            RouteLeg safe,
            RouteLeg reference) {
        switch (mode) {
            case INTERNAL_SAFE -> {
                if (direction != null || crossing != null || safe == null || reference != null) {
                    throw new IllegalArgumentException("invalid INTERNAL_SAFE route shape");
                }
            }
            case CROSS_BOUNDARY_OUTBOUND -> validateCrossBoundary(
                    SixthRingPortal.Direction.OUTBOUND,
                    SixthRingPortal.BoundaryRole.OUTER_EXIT,
                    direction, crossing, safe, reference);
            case CROSS_BOUNDARY_INBOUND -> validateCrossBoundary(
                    SixthRingPortal.Direction.INBOUND,
                    SixthRingPortal.BoundaryRole.INNER_ENTRY,
                    direction, crossing, safe, reference);
            case EXTERNAL_ONLY -> {
                if (direction != null || crossing != null || safe != null || reference == null) {
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
            RouteLeg safe,
            RouteLeg reference) {
        if (direction != expectedDirection || crossing == null
                || crossing.direction() != expectedDirection
                || crossing.boundaryRole() != expectedRole
                || safe == null || reference == null) {
            throw new IllegalArgumentException("invalid cross-boundary route shape");
        }
    }
}
