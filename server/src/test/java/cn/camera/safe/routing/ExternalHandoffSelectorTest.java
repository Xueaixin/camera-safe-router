package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHandoffSelectorTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final SixthRingBoundary BOUNDARY = new SixthRingBoundary(
            square(0, 0, 0.01, 0.01),
            square(-0.005, -0.005, 0.015, 0.015),
            "test-boundary");
    private final ExternalHandoffSelector selector = new ExternalHandoffSelector();

    @Test
    void selectsTheFirstReliablyOutsidePointForOutboundRoutes() {
        ExternalHandoffPoint handoff = selector.select(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                List.of(point(0.015, 0.005), point(0.017, 0.005), point(0.02, 0.005)));

        assertThat(handoff.coordinate()).isEqualTo(point(0.02, 0.005));
        assertThat(handoff.boundaryClearanceMeters()).isGreaterThan(500);
        assertThat(handoff.poiSearchRadiusMeters()).isEqualTo(200);
    }

    @Test
    void selectsTheLastReliablyOutsidePointBeforeAnInboundRouteReachesTheRing() {
        ExternalHandoffPoint handoff = selector.select(
                SixthRingPortal.Direction.INBOUND,
                BOUNDARY,
                List.of(
                        point(-0.03, 0.005),
                        point(-0.01, 0.005),
                        point(-0.007, 0.005),
                        point(0, 0.005)));

        assertThat(handoff.coordinate()).isEqualTo(point(-0.01, 0.005));
        assertThat(handoff.poiSearchRadiusMeters()).isEqualTo(200);
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
