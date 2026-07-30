package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineSegment;

import java.util.List;

public final class GeoDistance {
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    public static double meters(Wgs84Coordinate first, Wgs84Coordinate second) {
        double lat1 = Math.toRadians(first.lat());
        double lat2 = Math.toRadians(second.lat());
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(second.lng() - first.lng());
        double sinLat = Math.sin(deltaLat / 2);
        double sinLng = Math.sin(deltaLng / 2);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static double minimumMeters(Wgs84Coordinate point, PointList line) {
        if (line.size() == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (line.size() == 1) {
            return meters(point, new Wgs84Coordinate(line.getLon(0), line.getLat(0)));
        }
        double minimum = Double.POSITIVE_INFINITY;
        Coordinate origin = new Coordinate(0, 0);
        for (int index = 1; index < line.size(); index++) {
            LineSegment segment = new LineSegment(
                    project(point, line.getLon(index - 1), line.getLat(index - 1)),
                    project(point, line.getLon(index), line.getLat(index)));
            minimum = Math.min(minimum, segment.distance(origin));
        }
        return minimum;
    }

    public static double minimumMeters(Wgs84Coordinate point, List<Wgs84Coordinate> line) {
        if (line.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (line.size() == 1) {
            return meters(point, line.getFirst());
        }
        double minimum = Double.POSITIVE_INFINITY;
        Coordinate origin = new Coordinate(0, 0);
        for (int index = 1; index < line.size(); index++) {
            Wgs84Coordinate previous = line.get(index - 1);
            Wgs84Coordinate current = line.get(index);
            LineSegment segment = new LineSegment(
                    project(point, previous.lng(), previous.lat()),
                    project(point, current.lng(), current.lat()));
            minimum = Math.min(minimum, segment.distance(origin));
        }
        return minimum;
    }

    public static Envelope envelope(Wgs84Coordinate point, double radiusMeters) {
        double latitudeDelta = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS);
        double longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(point.lat())));
        double longitudeDelta = latitudeDelta / longitudeScale;
        return new Envelope(
                point.lng() - longitudeDelta,
                point.lng() + longitudeDelta,
                point.lat() - latitudeDelta,
                point.lat() + latitudeDelta);
    }

    public static Envelope expandedEnvelope(List<Wgs84Coordinate> line, double radiusMeters) {
        Envelope envelope = new Envelope();
        line.forEach(point -> envelope.expandToInclude(point.lng(), point.lat()));
        if (envelope.isNull()) {
            return envelope;
        }
        Wgs84Coordinate center = new Wgs84Coordinate(
                (envelope.getMinX() + envelope.getMaxX()) / 2,
                (envelope.getMinY() + envelope.getMaxY()) / 2);
        Envelope expansion = envelope(center, radiusMeters);
        envelope.expandBy(expansion.getWidth() / 2, expansion.getHeight() / 2);
        return envelope;
    }

    private static Coordinate project(Wgs84Coordinate origin, double lng, double lat) {
        double x = Math.toRadians(lng - origin.lng()) * EARTH_RADIUS_METERS
                * Math.cos(Math.toRadians(origin.lat()));
        double y = Math.toRadians(lat - origin.lat()) * EARTH_RADIUS_METERS;
        return new Coordinate(x, y);
    }
}
