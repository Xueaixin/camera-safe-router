package cn.camera.safe.application;

import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.routing.SixthRingBoundary;
import cn.camera.safe.routing.SixthRingRoutingManager;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlledAreaQueryServiceTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void returnsGcj02MultiPolygonAndPreservesInteriorRings() {
        LinearRing shell = GEOMETRY_FACTORY.createLinearRing(new Coordinate[] {
                new Coordinate(116.0, 40.0), new Coordinate(116.2, 40.0),
                new Coordinate(116.2, 40.2), new Coordinate(116.0, 40.2),
                new Coordinate(116.0, 40.0)
        });
        LinearRing hole = GEOMETRY_FACTORY.createLinearRing(new Coordinate[] {
                new Coordinate(116.05, 40.05), new Coordinate(116.1, 40.05),
                new Coordinate(116.1, 40.1), new Coordinate(116.05, 40.1),
                new Coordinate(116.05, 40.05)
        });
        Polygon polygon = GEOMETRY_FACTORY.createPolygon(shell, new LinearRing[] {hole});
        SixthRingRoutingManager manager = mock(SixthRingRoutingManager.class);
        when(manager.isReady()).thenReturn(true);
        when(manager.requireBoundary()).thenReturn(new SixthRingBoundary(polygon, "boundary-v2"));
        when(manager.isBoundaryApprovedForProduction()).thenReturn(false);
        SixthRingProperties properties = new SixthRingProperties(
                "boundary.geojson", false, 50, 1000, 100, 100,
                2_000_000, Duration.ofSeconds(5));

        var response = new ControlledAreaQueryService(
                manager, new CoordinateConverter(), properties).current();

        assertThat(response.boundaryVersion()).isEqualTo("boundary-v2");
        assertThat(response.coordinateSystem().name()).isEqualTo("GCJ02");
        assertThat(response.geometry().type()).isEqualTo("MultiPolygon");
        assertThat(response.geometry().coordinates()).hasSize(1);
        assertThat(response.geometry().coordinates().getFirst()).hasSize(2);
        assertThat(response.geometry().coordinates().getFirst().getFirst().getFirst())
                .isNotEqualTo(response.geometry().coordinates().getFirst().getFirst().get(1));
        assertThat(response.cameraOutsideMarginMeters()).isEqualTo(50);
        assertThat(response.approvedForProduction()).isFalse();
    }
}
