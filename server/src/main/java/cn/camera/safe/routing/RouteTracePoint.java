package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;

import java.util.Objects;

record RouteTracePoint(
        int geometryIndex,
        Wgs84Coordinate coordinate,
        String roadName,
        RoadClass roadClass,
        boolean roadClassLink,
        RoadEnvironment roadEnvironment) {

    RouteTracePoint {
        if (geometryIndex < 0) {
            throw new IllegalArgumentException("geometry index must be non-negative");
        }
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(roadClass, "roadClass");
        Objects.requireNonNull(roadEnvironment, "roadEnvironment");
        roadName = roadName == null ? "" : roadName;
    }
}
