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
        NavigationHandoff navigationHandoff,
        ExternalHandoff externalHandoff,
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
                if (boundaryDirection != null || boundaryCrossing != null || navigationHandoff != null
                        || externalHandoff != null
                        || safeSegment == null || referenceSegment != null) {
                    throw new IllegalArgumentException("invalid INTERNAL_SAFE response shape");
                }
            }
            case CROSS_BOUNDARY_OUTBOUND -> validateCrossBoundary(
                    BoundaryDirection.OUTBOUND,
                    BoundaryRole.OUTER_EXIT,
                    boundaryDirection, boundaryCrossing, navigationHandoff, externalHandoff,
                    safeSegment, referenceSegment);
            case CROSS_BOUNDARY_INBOUND -> validateCrossBoundary(
                    BoundaryDirection.INBOUND,
                    BoundaryRole.INNER_ENTRY,
                    boundaryDirection, boundaryCrossing, navigationHandoff, externalHandoff,
                    safeSegment, referenceSegment);
            case EXTERNAL_ONLY -> {
                if (boundaryDirection != null || boundaryCrossing != null || navigationHandoff != null
                        || externalHandoff != null
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
            NavigationHandoff navigationHandoff,
            ExternalHandoff externalHandoff,
            RouteSegment safe,
            RouteSegment reference) {
        if (direction != expectedDirection || crossing == null
                || crossing.direction() != expectedDirection
                || crossing.boundaryRole() != expectedRole
                || safe == null || reference == null
                || (navigationHandoff == null) != (externalHandoff == null)) {
            throw new IllegalArgumentException("invalid cross-boundary response shape");
        }
        if (navigationHandoff != null) {
            if (navigationHandoff.type() == NavigationHandoffType.HIGHWAY
                    && (expectedDirection != BoundaryDirection.INBOUND
                            || externalHandoff.poiSearchRadiusMeters() != 0)) {
                throw new IllegalArgumentException(
                        "highway handoff is only valid inbound with POI search disabled");
            }
            OutputCoordinate safeJoin = expectedDirection == BoundaryDirection.OUTBOUND
                    ? safe.geometry().getLast()
                    : safe.geometry().getFirst();
            OutputCoordinate referenceJoin = expectedDirection == BoundaryDirection.OUTBOUND
                    ? reference.geometry().getFirst()
                    : reference.geometry().getLast();
            if (!navigationHandoff.gcj02().equals(safeJoin)
                    || !navigationHandoff.gcj02().equals(referenceJoin)) {
                throw new IllegalArgumentException(
                        "navigation handoff must equal the safe/reference segment join");
            }
        }
    }
}
