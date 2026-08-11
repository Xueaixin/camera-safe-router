package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import static org.assertj.core.api.Assertions.assertThat;

class SixthRingBoundaryCameraScopeTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final SixthRingBoundary BOUNDARY = new SixthRingBoundary(
            GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                    new Coordinate(116.0, 40.0),
                    new Coordinate(116.1, 40.0),
                    new Coordinate(116.1, 40.1),
                    new Coordinate(116.0, 40.1),
                    new Coordinate(116.0, 40.0)
            }),
            "scope-v1");

    @Test
    void controlsInsideBoundaryAndOnlyTheConfiguredOutsideMargin() {
        assertThat(BOUNDARY.controls(new Wgs84Coordinate(116.05, 40.05), 50)).isTrue();
        assertThat(BOUNDARY.controls(new Wgs84Coordinate(116.1003, 40.05), 50)).isTrue();
        assertThat(BOUNDARY.controls(new Wgs84Coordinate(116.1010, 40.05), 50)).isFalse();
    }
}
