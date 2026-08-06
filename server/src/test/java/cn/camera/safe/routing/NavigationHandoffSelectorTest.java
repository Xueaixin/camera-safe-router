package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationHandoffSelectorTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final SixthRingBoundary BOUNDARY = new SixthRingBoundary(
            square(0, 0, 0.01, 0.01),
            square(-0.005, -0.005, 0.015, 0.015),
            "test-boundary");
    private final NavigationHandoffSelector selector = new NavigationHandoffSelector();

    @Test
    void selectsAnOutsideOrdinaryRoadPointInsteadOfTheBoundaryCoordinate() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.015, 0.005),
                point(0.017, 0.005),
                point(0.020, 0.005),
                point(0.024, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.OUTBOUND,
                        BOUNDARY,
                        ordinarySafeRoute(),
                        anchored(geometry, List.of(
                                trace(0, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(1, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(2, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(3, geometry, RoadClass.UNCLASSIFIED, false))))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.020, 0.005));
        assertThat(handoff.segment()).isEqualTo(NavigationHandoffPoint.Segment.REFERENCE);
        assertThat(handoff.geometryIndex()).isEqualTo(2);
        assertThat(handoff.boundaryClearanceMeters()).isGreaterThan(500);
    }

    @Test
    void skipsMotorwayMainlineAndLinkPoints() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.015, 0.005),
                point(0.017, 0.005),
                point(0.020, 0.005),
                point(0.024, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.OUTBOUND,
                        BOUNDARY,
                        ordinarySafeRoute(),
                        anchored(geometry, List.of(
                                trace(0, geometry, RoadClass.MOTORWAY, false),
                                trace(1, geometry, RoadClass.MOTORWAY, false),
                                trace(2, geometry, RoadClass.PRIMARY, true),
                                trace(2, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(3, geometry, RoadClass.UNCLASSIFIED, false))))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.020, 0.005));
        assertThat(handoff.roadName()).isEqualTo("普通道路");
    }

    @Test
    void doesNotExposeAMotorwayMainlineWhenNoOrdinaryHandoffCanBeProved() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.015, 0.005),
                point(0.017, 0.005),
                point(0.020, 0.005),
                point(0.024, 0.005));

        Optional<NavigationHandoffPoint> handoff = selector.select(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                ordinarySafeRoute(),
                anchored(geometry, List.of(
                        trace(0, geometry, RoadClass.MOTORWAY, false),
                        trace(1, geometry, RoadClass.MOTORWAY, false),
                        trace(2, geometry, RoadClass.MOTORWAY, false),
                        trace(3, geometry, RoadClass.MOTORWAY, false))));

        assertThat(handoff).isEmpty();
    }

    @Test
    void selectsTheLastOuterRoadPointBeforeAnInboundBoundaryCrossing() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.024, 0.005),
                point(0.020, 0.005),
                point(0.017, 0.005),
                point(0.015, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.INBOUND,
                        BOUNDARY,
                        ordinarySafeRoute(),
                        anchored(geometry, List.of(
                                trace(0, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(1, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(2, geometry, RoadClass.UNCLASSIFIED, false),
                                trace(3, geometry, RoadClass.UNCLASSIFIED, false))))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.020, 0.005));
        assertThat(handoff.segment()).isEqualTo(NavigationHandoffPoint.Segment.REFERENCE);
        assertThat(handoff.geometryIndex()).isEqualTo(1);
    }

    @Test
    void selectsTheFirstInnerOrdinaryRoadPointAfterAnInboundMotorwayExit() {
        List<Wgs84Coordinate> referenceGeometry = List.of(
                point(0.024, 0.005),
                point(0.018, 0.005),
                point(0.014, 0.005),
                point(0.010, 0.005));
        List<Wgs84Coordinate> safeGeometry = List.of(
                point(0.010, 0.005),
                point(0.009, 0.005),
                point(0.008, 0.005),
                point(0.007, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.INBOUND,
                        BOUNDARY,
                        traced(safeGeometry, List.of(
                                trace(0, safeGeometry, RoadClass.MOTORWAY, false),
                                trace(1, safeGeometry, RoadClass.PRIMARY, true),
                                trace(1, safeGeometry, RoadClass.UNCLASSIFIED, false),
                                trace(2, safeGeometry, RoadClass.UNCLASSIFIED, false),
                                trace(3, safeGeometry, RoadClass.UNCLASSIFIED, false))),
                        anchored(referenceGeometry, List.of(
                                trace(0, referenceGeometry, RoadClass.MOTORWAY, false),
                                trace(1, referenceGeometry, RoadClass.MOTORWAY, false),
                                trace(2, referenceGeometry, RoadClass.MOTORWAY, false),
                                trace(3, referenceGeometry, RoadClass.MOTORWAY, false))))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.008, 0.005));
        assertThat(handoff.segment()).isEqualTo(NavigationHandoffPoint.Segment.SAFE);
        assertThat(handoff.geometryIndex()).isEqualTo(2);
        assertThat(handoff.roadName()).isEqualTo("普通道路");
    }

    @Test
    void leavesInboundHandoffEmptyWhenNoOrdinaryRoadAfterTheMotorwayCanBeProved() {
        List<Wgs84Coordinate> referenceGeometry = List.of(
                point(0.024, 0.005),
                point(0.018, 0.005),
                point(0.014, 0.005),
                point(0.010, 0.005));
        List<Wgs84Coordinate> safeGeometry = List.of(
                point(0.010, 0.005),
                point(0.009, 0.005),
                point(0.008, 0.005));

        Optional<NavigationHandoffPoint> handoff = selector.select(
                SixthRingPortal.Direction.INBOUND,
                BOUNDARY,
                traced(safeGeometry, List.of(
                        trace(0, safeGeometry, RoadClass.MOTORWAY, false),
                        trace(1, safeGeometry, RoadClass.MOTORWAY, false),
                        trace(2, safeGeometry, RoadClass.MOTORWAY, false))),
                anchored(referenceGeometry, List.of(
                        trace(0, referenceGeometry, RoadClass.MOTORWAY, false),
                        trace(1, referenceGeometry, RoadClass.MOTORWAY, false),
                        trace(2, referenceGeometry, RoadClass.MOTORWAY, false),
                        trace(3, referenceGeometry, RoadClass.MOTORWAY, false))));

        assertThat(handoff).isEmpty();
    }

    private static TracedRouteLeg ordinarySafeRoute() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.010, 0.005),
                point(0.008, 0.005),
                point(0.006, 0.005));
        return traced(geometry, List.of(
                trace(0, geometry, RoadClass.UNCLASSIFIED, false),
                trace(1, geometry, RoadClass.UNCLASSIFIED, false),
                trace(2, geometry, RoadClass.UNCLASSIFIED, false)));
    }

    private static TracedRouteLeg traced(
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> trace) {
        return new TracedRouteLeg(new RouteLeg(1_000, 100_000, geometry), trace);
    }

    private static PortalAnchoredRoute anchored(
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> trace) {
        return new PortalAnchoredRoute(
                new EngineRoute(1_000, 100_000, geometry, 0, 0, 0),
                trace);
    }

    private static RouteTracePoint trace(
            int index,
            List<Wgs84Coordinate> geometry,
            RoadClass roadClass,
            boolean link) {
        return new RouteTracePoint(
                index,
                geometry.get(index),
                roadClass == RoadClass.UNCLASSIFIED ? "普通道路" : "高速道路",
                roadClass,
                link,
                RoadEnvironment.ROAD);
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
