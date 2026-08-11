package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;

import java.util.Objects;

public record RouteTracePoint(
        int geometryIndex,
        Wgs84Coordinate coordinate,
        String roadName,
        RoadClass roadClass,
        boolean roadClassLink,
        RoadEnvironment roadEnvironment,
        int originalEdgeKey) {

    public RouteTracePoint {
        if (geometryIndex < 0) {
            throw new IllegalArgumentException("geometry index must be non-negative");
        }
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(roadClass, "roadClass");
        Objects.requireNonNull(roadEnvironment, "roadEnvironment");
        if (originalEdgeKey < -1) {
            throw new IllegalArgumentException("original edge key must be non-negative or unknown");
        }
        roadName = roadName == null ? "" : roadName;
    }

    RouteTracePoint(
            int geometryIndex,
            Wgs84Coordinate coordinate,
            String roadName,
            RoadClass roadClass,
            boolean roadClassLink,
            RoadEnvironment roadEnvironment) {
        this(geometryIndex, coordinate, roadName, roadClass, roadClassLink,
                roadEnvironment, -1);
    }

    public int baseEdgeId() {
        return originalEdgeKey < 0 ? -1 : originalEdgeKey / 2;
    }
}
