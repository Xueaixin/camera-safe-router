package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.Objects;
import java.util.Optional;

public final class SixthRingBoundary {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final Geometry controlledArea;
    private final Geometry sixthRingArea;
    private final Geometry tongzhouArea;
    private final String version;

    public SixthRingBoundary(Geometry controlledArea, String version) {
        this(controlledArea, null, null, version);
    }

    public SixthRingBoundary(
            Geometry controlledArea,
            Geometry tongzhouArea,
            String version) {
        this(controlledArea, null, tongzhouArea, version);
    }

    public SixthRingBoundary(
            Geometry controlledArea,
            Geometry sixthRingArea,
            Geometry tongzhouArea,
            String version) {
        Objects.requireNonNull(controlledArea, "controlledArea");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("controlled-area boundary version is required");
        }

        Geometry copy = controlledArea.copy();
        if (!(copy instanceof Polygon || copy instanceof MultiPolygon)
                || copy.isEmpty() || !copy.isValid()) {
            throw new IllegalArgumentException(
                    "controlled-area boundary must be a non-empty valid Polygon or MultiPolygon");
        }
        Geometry sixthRingCopy = polygonalCopy(sixthRingArea, "Sixth Ring area");
        Geometry tongzhouCopy = polygonalCopy(tongzhouArea, "Tongzhou area");
        if (sixthRingCopy != null && sixthRingCopy.difference(copy).getArea() >= 1e-12) {
            throw new IllegalArgumentException("controlled area must cover Sixth Ring area");
        }
        if (tongzhouCopy != null && tongzhouCopy.difference(copy).getArea() >= 1e-12) {
            throw new IllegalArgumentException("controlled area must cover Tongzhou area");
        }
        if (sixthRingCopy != null && tongzhouCopy != null) {
            Geometry union = sixthRingCopy.union(tongzhouCopy);
            if (union.difference(copy).getArea() >= 1e-12
                    || copy.difference(union).getArea() >= 1e-12) {
                throw new IllegalArgumentException(
                        "controlled area must equal the union of Sixth Ring and Tongzhou areas");
            }
        }

        this.controlledArea = copy;
        this.sixthRingArea = sixthRingCopy;
        this.tongzhouArea = tongzhouCopy;
        this.version = version;
    }

    public Geometry controlledArea() {
        return controlledArea.copy();
    }

    public Optional<Geometry> sixthRingArea() {
        return Optional.ofNullable(sixthRingArea).map(Geometry::copy);
    }

    public Optional<Geometry> tongzhouArea() {
        return Optional.ofNullable(tongzhouArea).map(Geometry::copy);
    }

    private static Geometry polygonalCopy(Geometry geometry, String label) {
        if (geometry == null) {
            return null;
        }
        Geometry copy = geometry.copy();
        if (!(copy instanceof Polygon || copy instanceof MultiPolygon)
                || copy.isEmpty() || !copy.isValid()) {
            throw new IllegalArgumentException(
                    label + " must be a non-empty valid Polygon or MultiPolygon");
        }
        return copy;
    }

    public String version() {
        return version;
    }

    public Location locate(Wgs84Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        var point = GEOMETRY_FACTORY.createPoint(
                new Coordinate(coordinate.lng(), coordinate.lat()));
        if (controlledArea.getBoundary().covers(point)) {
            return Location.BOUNDARY;
        }
        if (controlledArea.covers(point)) {
            return Location.INSIDE;
        }
        return Location.OUTSIDE;
    }

    public double outsideDistanceMeters(Wgs84Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        if (locate(coordinate) != Location.OUTSIDE) {
            return 0;
        }
        var point = GEOMETRY_FACTORY.createPoint(
                new Coordinate(coordinate.lng(), coordinate.lat()));
        Coordinate nearest = DistanceOp.nearestPoints(controlledArea.getBoundary(), point)[0];
        return GeoDistance.meters(
                coordinate,
                new Wgs84Coordinate(nearest.x, nearest.y));
    }

    public boolean controls(Wgs84Coordinate coordinate, double outsideMarginMeters) {
        if (!Double.isFinite(outsideMarginMeters) || outsideMarginMeters < 0) {
            throw new IllegalArgumentException("outside margin must be non-negative");
        }
        return outsideDistanceMeters(coordinate) <= outsideMarginMeters;
    }

    public enum Location {
        INSIDE,
        OUTSIDE,
        BOUNDARY
    }
}
