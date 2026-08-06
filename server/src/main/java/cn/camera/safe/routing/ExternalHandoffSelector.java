package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Selects an outer-side route point whose POI search circle cannot cross the ring boundary. */
public final class ExternalHandoffSelector {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double TARGET_CLEARANCE_METERS = 250;
    private static final int MAX_POI_RADIUS_METERS = 200;
    private static final int MIN_RELIABLE_POI_RADIUS_METERS = 50;
    private static final int CLEARANCE_MARGIN_METERS = 25;

    public ExternalHandoffPoint select(
            SixthRingPortal.Direction direction,
            SixthRingBoundary boundary,
            List<Wgs84Coordinate> referenceGeometry) {
        Polygon outer = boundary.outerPolygon();
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < referenceGeometry.size(); index++) {
            Wgs84Coordinate coordinate = referenceGeometry.get(index);
            Point point = GEOMETRY_FACTORY.createPoint(
                    new Coordinate(coordinate.lng(), coordinate.lat()));
            if (outer.covers(point)) {
                continue;
            }
            Coordinate nearest = DistanceOp.nearestPoints(outer.getBoundary(), point)[0];
            double clearance = GeoDistance.meters(
                    coordinate, new Wgs84Coordinate(nearest.x, nearest.y));
            candidates.add(new Candidate(index, coordinate, clearance));
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("reference route has no point outside the sixth ring");
        }

        List<Candidate> reliable = candidates.stream()
                .filter(candidate -> candidate.clearanceMeters() >= TARGET_CLEARANCE_METERS)
                .toList();
        Candidate selected;
        if (!reliable.isEmpty()) {
            selected = direction == SixthRingPortal.Direction.OUTBOUND
                    ? reliable.getFirst() : reliable.getLast();
        } else {
            selected = candidates.stream()
                    .max(Comparator.comparingDouble(Candidate::clearanceMeters))
                    .orElseThrow();
        }
        int radius = (int) Math.floor(Math.min(
                MAX_POI_RADIUS_METERS,
                Math.max(0, selected.clearanceMeters() - CLEARANCE_MARGIN_METERS)));
        if (radius < MIN_RELIABLE_POI_RADIUS_METERS) {
            radius = 0;
        }
        return new ExternalHandoffPoint(
                selected.coordinate(), selected.clearanceMeters(), radius);
    }

    private record Candidate(
            int geometryIndex,
            Wgs84Coordinate coordinate,
            double clearanceMeters) {
    }
}
