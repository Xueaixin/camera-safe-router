package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SixthRingRouteShapeValidatorTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final SixthRingBoundary BOUNDARY = new SixthRingBoundary(
            square(0, 0, 0.01, 0.01),
            square(-0.005, -0.005, 0.015, 0.015),
            "test-boundary");
    private final SixthRingRouteShapeValidator validator = new SixthRingRouteShapeValidator();

    @Test
    void acceptsAnOutboundRouteThatStaysInTheBandOrOutsideAfterTheOuterExit() {
        RouteLeg safe = leg(point(0.005, 0.005), point(0.015, 0.005));
        RouteLeg reference = leg(
                point(0.015, 0.005), point(0.016, 0.005), point(0.03, 0.005));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND, BOUNDARY, safe, reference)).isTrue();
    }

    @Test
    void rejectsAnOutboundReferenceRouteThatReentersTheInnerCore() {
        RouteLeg safe = leg(point(0.005, 0.005), point(0.015, 0.005));
        RouteLeg reference = leg(
                point(0.015, 0.005), point(0.005, 0.005), point(0.03, 0.005));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND, BOUNDARY, safe, reference)).isFalse();
    }

    @Test
    void acceptsAnInboundRouteThatEntersOnceAndThenRemainsInside() {
        RouteLeg reference = leg(point(-0.03, 0.005), point(0, 0.005));
        RouteLeg safe = leg(point(0, 0.005), point(0.005, 0.005));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.INBOUND, BOUNDARY, safe, reference)).isTrue();
    }

    private static RouteLeg leg(Wgs84Coordinate... geometry) {
        return new RouteLeg(1, 1, List.of(geometry));
    }

    private static Wgs84Coordinate point(double lng, double lat) {
        return new Wgs84Coordinate(lng, lat);
    }

    private static Polygon square(double minLng, double minLat, double maxLng, double maxLat) {
        return GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(minLng, minLat),
                new Coordinate(maxLng, minLat),
                new Coordinate(maxLng, maxLat),
                new Coordinate(minLng, maxLat),
                new Coordinate(minLng, minLat)
        });
    }
}
