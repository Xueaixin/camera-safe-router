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
            square(-0.005, -0.005, 0.015, 0.015),
            "test-boundary");
    private final NavigationHandoffSelector selector = new NavigationHandoffSelector();

    @Test
    void choosesTheNearestOrdinaryRoadWithTwoHundredFiftyMeterClearance() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.015, 0.005),
                point(0.017, 0.005),
                point(0.019, 0.005),
                point(0.024, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.OUTBOUND,
                        BOUNDARY,
                        anchored(geometry, traces(geometry, RoadClass.UNCLASSIFIED, false)))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.019, 0.005));
        assertThat(handoff.segment()).isEqualTo(NavigationHandoffPoint.Segment.REFERENCE);
        assertThat(handoff.type()).isEqualTo(NavigationHandoffPoint.Type.ORDINARY_ROAD);
        assertThat(handoff.geometryIndex()).isEqualTo(2);
        assertThat(handoff.boundaryClearanceMeters()).isGreaterThan(250);
    }

    @Test
    void fallsBackToMaximumPositiveClearanceWithinFiveHundredMeters() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.015, 0.005),
                point(0.016, 0.005),
                point(0.017, 0.005),
                point(0.024, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.OUTBOUND,
                        BOUNDARY,
                        anchored(geometry, traces(geometry, RoadClass.UNCLASSIFIED, false)))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.017, 0.005));
        assertThat(handoff.boundaryClearanceMeters()).isBetween(200.0, 250.0);
    }

    @Test
    void scansTheFinalFiveHundredMetersBeforeAnInboundCrossing() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.024, 0.005),
                point(0.019, 0.005),
                point(0.017, 0.005),
                point(0.015, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                        SixthRingPortal.Direction.INBOUND,
                        BOUNDARY,
                        anchored(geometry, traces(geometry, RoadClass.UNCLASSIFIED, false)))
                .orElseThrow();

        assertThat(handoff.coordinate()).isEqualTo(point(0.019, 0.005));
        assertThat(handoff.geometryIndex()).isEqualTo(1);
    }

    @Test
    void rejectsMotorwaysLinksTunnelsAndUncertainRoadClasses() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.015, 0.005),
                point(0.016, 0.005),
                point(0.017, 0.005),
                point(0.019, 0.005),
                point(0.024, 0.005));
        List<RouteTracePoint> trace = List.of(
                trace(0, geometry, RoadClass.MOTORWAY, false, RoadEnvironment.ROAD),
                trace(1, geometry, RoadClass.PRIMARY, true, RoadEnvironment.ROAD),
                trace(2, geometry, RoadClass.PRIMARY, false, RoadEnvironment.TUNNEL),
                trace(3, geometry, RoadClass.OTHER, false, RoadEnvironment.ROAD),
                trace(4, geometry, RoadClass.UNCLASSIFIED, false, RoadEnvironment.ROAD));

        Optional<NavigationHandoffPoint> handoff = selector.select(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                anchored(geometry, trace));

        assertThat(handoff).isEmpty();
    }

    @Test
    void selectsAnInboundHighwayPointAboutThreeHundredMetersBeforeTheBoundary() {
        List<Wgs84Coordinate> geometry = List.of(
                point(0.024, 0.005),
                point(0.019, 0.005),
                point(0.017, 0.005),
                point(0.015, 0.005));

        NavigationHandoffPoint handoff = selector.select(
                SixthRingPortal.Direction.INBOUND,
                BOUNDARY,
                anchored(geometry, traces(geometry, RoadClass.MOTORWAY, false)))
                .orElseThrow();

        assertThat(handoff.type()).isEqualTo(NavigationHandoffPoint.Type.HIGHWAY);
        assertThat(handoff.segment()).isEqualTo(NavigationHandoffPoint.Segment.REFERENCE);
        assertThat(handoff.geometryIndex()).isEqualTo(2);
        assertThat(handoff.fractionFromPrevious()).isBetween(0.0, 1.0);
        assertThat(routeDistanceToBoundary(geometry, handoff)).isBetween(295.0, 305.0);
        assertThat(BOUNDARY.locate(handoff.coordinate()))
                .isEqualTo(SixthRingBoundary.Location.OUTSIDE);
    }

    private static double routeDistanceToBoundary(
            List<Wgs84Coordinate> geometry,
            NavigationHandoffPoint handoff) {
        double distance = GeoDistance.meters(
                handoff.coordinate(), geometry.get(handoff.geometryIndex()));
        for (int index = handoff.geometryIndex() + 1; index < geometry.size(); index++) {
            distance += GeoDistance.meters(geometry.get(index - 1), geometry.get(index));
        }
        return distance;
    }

    private static PortalAnchoredRoute anchored(
            List<Wgs84Coordinate> geometry,
            List<RouteTracePoint> trace) {
        return new PortalAnchoredRoute(
                new EngineRoute(1_000, 100_000, geometry, 0, 0, 0),
                trace);
    }

    private static List<RouteTracePoint> traces(
            List<Wgs84Coordinate> geometry,
            RoadClass roadClass,
            boolean link) {
        return java.util.stream.IntStream.range(0, geometry.size())
                .mapToObj(index -> trace(
                        index, geometry, roadClass, link, RoadEnvironment.ROAD))
                .toList();
    }

    private static RouteTracePoint trace(
            int index,
            List<Wgs84Coordinate> geometry,
            RoadClass roadClass,
            boolean link,
            RoadEnvironment environment) {
        return new RouteTracePoint(
                index,
                geometry.get(index),
                roadClass == RoadClass.UNCLASSIFIED ? "普通道路" : "受限道路",
                roadClass,
                link,
                environment);
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
