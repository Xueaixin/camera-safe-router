package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.Objects;

public final class SixthRingBoundary {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final Polygon innerPolygon;
    private final Polygon outerPolygon;
    private final String version;

    public SixthRingBoundary(Polygon innerPolygon, Polygon outerPolygon, String version) {
        Objects.requireNonNull(innerPolygon, "innerPolygon");
        Objects.requireNonNull(outerPolygon, "outerPolygon");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("sixth-ring boundary version is required");
        }

        Polygon innerCopy = (Polygon) innerPolygon.copy();
        Polygon outerCopy = (Polygon) outerPolygon.copy();
        if (innerCopy.isEmpty() || outerCopy.isEmpty()
                || !innerCopy.isValid() || !outerCopy.isValid()) {
            throw new IllegalArgumentException("sixth-ring boundary polygons must be non-empty and valid");
        }
        if (!outerCopy.covers(innerCopy)) {
            throw new IllegalArgumentException("outer sixth-ring polygon must cover the inner polygon");
        }

        this.innerPolygon = innerCopy;
        this.outerPolygon = outerCopy;
        this.version = version;
    }

    public Polygon innerPolygon() {
        return (Polygon) innerPolygon.copy();
    }

    public Polygon outerPolygon() {
        return (Polygon) outerPolygon.copy();
    }

    public String version() {
        return version;
    }

    public Location locate(Wgs84Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        var point = GEOMETRY_FACTORY.createPoint(
                new Coordinate(coordinate.lng(), coordinate.lat()));
        if (innerPolygon.covers(point)) {
            return Location.INSIDE;
        }
        if (!outerPolygon.covers(point)) {
            return Location.OUTSIDE;
        }
        return Location.BOUNDARY_BAND;
    }

    public enum Location {
        INSIDE,
        OUTSIDE,
        BOUNDARY_BAND
    }
}
