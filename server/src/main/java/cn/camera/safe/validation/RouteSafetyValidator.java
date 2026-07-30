package cn.camera.safe.validation;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.GeoDistance;
import cn.camera.safe.routing.RoutingSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class RouteSafetyValidator {
    public SafetyValidationResult validate(
            List<Wgs84Coordinate> route,
            RoutingSnapshot snapshot) {
        if (route.size() < 2) {
            throw new IllegalArgumentException("route geometry must contain at least two points");
        }
        List<RouteConflict> conflicts = new ArrayList<>();
        for (CameraPoint camera : snapshot.cameraIndex().query(
                GeoDistance.expandedEnvelope(route, snapshot.safetyRadiusMeters()))) {
            double distance = GeoDistance.minimumMeters(camera.wgs84(), route);
            if (distance <= snapshot.safetyRadiusMeters()) {
                conflicts.add(new RouteConflict(camera.id(), distance));
            }
        }
        return new SafetyValidationResult(conflicts);
    }

    public boolean isRestricted(Wgs84Coordinate point, RoutingSnapshot snapshot) {
        return !snapshot.cameraIndex().within(point, snapshot.safetyRadiusMeters()).isEmpty();
    }
}
