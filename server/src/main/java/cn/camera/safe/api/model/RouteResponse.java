package cn.camera.safe.api.model;

import cn.camera.safe.coordinate.CoordinateSystem;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RouteResponse(
        String routeId,
        CoordinateSystem coordinateSystem,
        RoutePlanningMode planningMode,
        String boundaryVersion,
        BoundaryDirection boundaryDirection,
        BoundaryCrossing boundaryCrossing,
        RouteSegment safeSegment,
        RouteSegment referenceSegment,
        double distanceMeters,
        long durationSeconds,
        int cameraConflictCount,
        String cameraSnapshotVersion,
        String blockedEdgeVersion,
        List<OutputCoordinate> geometry,
        List<RouteStep> steps) {

    public RouteResponse {
        geometry = List.copyOf(geometry);
        steps = List.copyOf(steps);
        if (cameraConflictCount != 0) {
            throw new IllegalArgumentException("successful route response must have zero camera conflicts");
        }
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("route response geometry requires at least two points");
        }
        switch (planningMode) {
            case INTERNAL_SAFE -> {
                if (boundaryDirection != null || boundaryCrossing != null
                        || safeSegment == null || referenceSegment != null) {
                    throw new IllegalArgumentException("invalid INTERNAL_SAFE response shape");
                }
            }
            case CROSS_BOUNDARY_OUTBOUND -> validateCrossBoundary(
                    BoundaryDirection.OUTBOUND,
                    BoundaryRole.OUTER_EXIT,
                    boundaryDirection, boundaryCrossing, safeSegment, referenceSegment);
            case CROSS_BOUNDARY_INBOUND -> validateCrossBoundary(
                    BoundaryDirection.INBOUND,
                    BoundaryRole.INNER_ENTRY,
                    boundaryDirection, boundaryCrossing, safeSegment, referenceSegment);
            case EXTERNAL_ONLY -> {
                if (boundaryDirection != null || boundaryCrossing != null
                        || safeSegment != null || referenceSegment == null) {
                    throw new IllegalArgumentException("invalid EXTERNAL_ONLY response shape");
                }
            }
        }
    }

    private static void validateCrossBoundary(
            BoundaryDirection expectedDirection,
            BoundaryRole expectedRole,
            BoundaryDirection direction,
            BoundaryCrossing crossing,
            RouteSegment safe,
            RouteSegment reference) {
        if (direction != expectedDirection || crossing == null
                || crossing.direction() != expectedDirection
                || crossing.boundaryRole() != expectedRole
                || safe == null || reference == null) {
            throw new IllegalArgumentException("invalid cross-boundary response shape");
        }
    }
}
