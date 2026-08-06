package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

/** Independent final check for the mode-specific sixth-ring transition shape. */
public final class SixthRingRouteShapeValidator {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double BOUNDARY_TOLERANCE_DEGREES = 0.00001;

    public boolean isValid(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            RouteLeg safeSegment,
            RouteLeg referenceSegment) {
        Polygon safeArea = direction == SixthRingPortal.Direction.OUTBOUND
                ? boundary.outerPolygon() : boundary.innerPolygon();
        Geometry bufferedSafeArea = safeArea.buffer(BOUNDARY_TOLERANCE_DEGREES);
        Geometry restrictedInterior = boundary.innerPolygon()
                .buffer(-BOUNDARY_TOLERANCE_DEGREES);
        if (restrictedInterior.isEmpty()) {
            restrictedInterior = boundary.innerPolygon();
        }
        return bufferedSafeArea.covers(lineString(safeSegment.geometry()))
                && !restrictedInterior.intersects(lineString(referenceSegment.geometry()));
    }

    private static LineString lineString(List<Wgs84Coordinate> geometry) {
        Coordinate[] coordinates = geometry.stream()
                .map(point -> new Coordinate(point.lng(), point.lat()))
                .toArray(Coordinate[]::new);
        return GEOMETRY_FACTORY.createLineString(coordinates);
    }
}
