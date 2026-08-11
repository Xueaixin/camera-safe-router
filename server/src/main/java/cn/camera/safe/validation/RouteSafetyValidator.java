package cn.camera.safe.validation;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.GeoDistance;
import cn.camera.safe.routing.PlannedRoute;
import cn.camera.safe.routing.RouteTraceSupport;
import cn.camera.safe.routing.RoutingSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class RouteSafetyValidator {
    public SafetyValidationResult validate(
            List<Wgs84Coordinate> route,
            RoutingSnapshot snapshot) {
        return validateApplicableGeometry(route, List.of(route), snapshot);
    }

    public SafetyValidationResult validate(
            PlannedRoute route,
            RoutingSnapshot snapshot) {
        if (route.trace().isEmpty()) {
            return validate(route.geometry(), snapshot);
        }
        List<List<Wgs84Coordinate>> applicableGeometry = RouteTraceSupport
                .edgeRuns(route.trace()).stream()
                .filter(run -> !snapshot.roadClassification()
                        .isHighwayMainline(run.baseEdgeId()))
                .map(RouteTraceSupport.EdgeRun::geometry)
                .filter(geometry -> geometry.size() >= 2)
                .toList();
        return validateApplicableGeometry(route.geometry(), applicableGeometry, snapshot);
    }

    private SafetyValidationResult validateApplicableGeometry(
            List<Wgs84Coordinate> envelopeGeometry,
            List<List<Wgs84Coordinate>> applicableGeometry,
            RoutingSnapshot snapshot) {
        if (envelopeGeometry.size() < 2) {
            throw new IllegalArgumentException("route geometry must contain at least two points");
        }
        if (applicableGeometry.isEmpty()) {
            return new SafetyValidationResult(List.of());
        }
        List<RouteConflict> conflicts = new ArrayList<>();
        for (CameraPoint camera : snapshot.cameraIndex().query(
                GeoDistance.expandedEnvelope(
                        envelopeGeometry, snapshot.safetyRadiusMeters()))) {
            double distance = applicableGeometry.stream()
                    .mapToDouble(geometry -> GeoDistance.minimumMeters(
                            camera.wgs84(), geometry))
                    .min()
                    .orElse(Double.POSITIVE_INFINITY);
            if (distance <= snapshot.safetyRadiusMeters()) {
                conflicts.add(new RouteConflict(camera.id(), distance));
            }
        }
        return new SafetyValidationResult(conflicts);
    }

    public boolean isRestricted(Wgs84Coordinate point, RoutingSnapshot snapshot) {
        return !snapshot.cameraIndex().within(point, snapshot.safetyRadiusMeters()).isEmpty();
    }

    public boolean isRestricted(
            Wgs84Coordinate point,
            RoutingSnapshot snapshot,
            int snappedBaseEdgeId) {
        return !snapshot.roadClassification().isHighwayMainline(snappedBaseEdgeId)
                && isRestricted(point, snapshot);
    }
}
