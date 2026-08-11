package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.List;
import java.util.BitSet;

import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class SixthRingRouteShapeValidatorTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final SixthRingBoundary BOUNDARY = new SixthRingBoundary(
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
        RouteLeg reference = leg(point(-0.03, 0.005), point(-0.005, 0.005));
        RouteLeg safe = leg(point(-0.005, 0.005), point(0.005, 0.005));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.INBOUND, BOUNDARY, safe, reference)).isTrue();
    }

    @Test
    void allowsHighwayToReenterGeometryButRejectsControlledOrdinaryRoadAfterRelease() {
        RoadClassificationIndex classification = classification(1);
        List<RouteTracePoint> allowed = trace(
                run(0, point(0.005, 0.005), point(0.016, 0.005)),
                run(1, point(0.016, 0.005), point(0.020, 0.005),
                        point(0.010, 0.006), point(0.030, 0.006)));
        List<RouteTracePoint> rejected = trace(
                run(0, point(0.005, 0.005), point(0.016, 0.005)),
                run(1, point(0.016, 0.005), point(0.020, 0.005)),
                run(2, point(0.020, 0.005), point(0.010, 0.005)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                allowed,
                classification)).isTrue();
        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                rejected,
                classification)).isFalse();
    }

    @Test
    void validatesInboundTraceByReversingItToTheCanonicalInsideToOutsideDirection() {
        RoadClassificationIndex classification = classification(1);
        List<RouteTracePoint> inbound = trace(
                run(2, point(-0.030, 0.005), point(-0.010, 0.005)),
                run(3, point(-0.010, 0.005), point(0.005, 0.005)),
                run(0, point(0.005, 0.005), point(0.007, 0.005)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.INBOUND,
                BOUNDARY,
                inbound,
                classification)).isTrue();
    }

    @Test
    void allowsASixthRingTollConnectorOnlyWhenTheChosenBranchLeavesTheArea() {
        RoadClassificationIndex classification = tollConnectorClassification();
        List<RouteTracePoint> outsideBranch = trace(
                run(0, point(0.005, 0.005), point(0.016, 0.005)),
                run(1, point(0.016, 0.005), point(0.010, 0.006)),
                run(2, point(0.010, 0.006), point(0.016, 0.006)),
                run(3, point(0.016, 0.006), point(0.030, 0.006)));
        List<RouteTracePoint> insideBranch = trace(
                run(0, point(0.005, 0.005), point(0.016, 0.005)),
                run(1, point(0.016, 0.005), point(0.010, 0.006)),
                run(2, point(0.010, 0.006), point(0.012, 0.006)),
                run(4, point(0.012, 0.006), point(0.010, 0.007)),
                run(3, point(0.010, 0.007), point(0.030, 0.007)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                outsideBranch,
                classification)).isTrue();
        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                insideBranch,
                classification)).isFalse();
    }

    @Test
    void acceptsAnOutboundRouteThroughVerifiedEntryAndExitTollCorridors() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> route = trace(
                run(0, point(0.005, 0.005), point(0.015, 0.005)),
                run(1, point(0.015, 0.005), point(0.016, 0.005)),
                run(2, point(0.016, 0.005), point(0.010, 0.006)),
                run(3, point(0.010, 0.006), point(0.010, 0.016)),
                run(4, point(0.010, 0.016), point(0.020, 0.016)),
                run(5, point(0.020, 0.016), point(0.025, 0.010)),
                run(6, point(0.025, 0.010), point(0.030, 0.005)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                route,
                tollCorridorClassification(),
                corridors)).isTrue();
    }

    @Test
    void acceptsAnInboundRouteByReversingVerifiedCorridorBlocks() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> inbound = trace(
                run(8, point(0.030, 0.005), point(0.016, 0.005)),
                run(2, point(0.016, 0.005), point(0.010, 0.006)),
                run(3, point(0.010, 0.006), point(0.010, 0.016)),
                run(4, point(0.010, 0.016), point(0.020, 0.016)),
                run(5, point(0.020, 0.016), point(0.025, 0.010)),
                run(6, point(0.025, 0.010), point(0.030, 0.005)),
                run(9, point(0.030, 0.005), point(0.015, 0.005)),
                run(10, point(0.015, 0.005), point(0.014, 0.005)),
                run(11, point(0.014, 0.005), point(0.010, 0.005)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.INBOUND,
                BOUNDARY,
                inbound,
                tollCorridorClassification(),
                corridors)).isTrue();
    }

    @Test
    void rejectsACompleteEntryCorridorThatBranchesToInsideOrdinaryRoad() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> branchInside = trace(
                run(0, point(0.005, 0.005), point(0.015, 0.005)),
                run(1, point(0.015, 0.005), point(0.016, 0.005)),
                run(2, point(0.016, 0.005), point(0.010, 0.006)),
                run(3, point(0.010, 0.006), point(0.010, 0.016)),
                run(7, point(0.010, 0.016), point(0.010, 0.010)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                branchInside,
                tollCorridorClassification(),
                corridors)).isFalse();
    }

    @Test
    void rejectsReverseTraversalOfAnEntryCorridor() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> reversed = trace(
                run(0, point(0.005, 0.005), point(0.015, 0.005)),
                run(1, point(0.015, 0.005), point(0.016, 0.005)),
                run(3, point(0.010, 0.006), point(0.010, 0.016)),
                run(2, point(0.016, 0.005), point(0.010, 0.006)),
                run(4, point(0.010, 0.016), point(0.020, 0.016)),
                run(5, point(0.020, 0.016), point(0.025, 0.010)),
                run(6, point(0.025, 0.010), point(0.030, 0.005)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                reversed,
                tollCorridorClassification(),
                corridors)).isFalse();
    }

    @Test
    void rejectsAnUnverifiedInteriorMotorwayLinkEvenInsideTheReleasedPhase() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> unknownRamp = trace(
                run(0, point(0.005, 0.005), point(0.015, 0.005)),
                run(1, point(0.015, 0.005), point(0.016, 0.005)),
                run(2, point(0.016, 0.005), point(0.010, 0.006)),
                run(3, point(0.010, 0.006), point(0.010, 0.016)),
                run(4, point(0.010, 0.016), point(0.020, 0.016)),
                run(7, point(0.020, 0.016), point(0.010, 0.012)),
                run(6, point(0.025, 0.010), point(0.030, 0.005)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                unknownRamp,
                tollCorridorClassification(),
                corridors)).isFalse();
    }

    @Test
    void rejectsAnUnverifiedOutsideRampFromOrdinaryRoadToTheSixthRing() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> unverifiedRamp = trace(
                run(0, point(0.005, 0.005), point(0.015, 0.005)),
                run(1, point(0.015, 0.005), point(0.016, 0.005)),
                run(5, point(0.016, 0.005), point(0.020, 0.016)),
                run(4, point(0.020, 0.016), point(0.030, 0.016)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                unverifiedRamp,
                tollCorridorClassification(),
                corridors)).isFalse();
    }

    @Test
    void acceptsAnOutsideHighwayInterchangeRampOntoTheSixthRing() {
        TollCorridorTopology corridors = tollCorridors();
        List<RouteTracePoint> highwayInterchange = trace(
                run(0, point(0.005, 0.005), point(0.015, 0.005)),
                run(1, point(0.015, 0.005), point(0.016, 0.005)),
                run(8, point(0.016, 0.005), point(0.020, 0.010)),
                run(5, point(0.020, 0.010), point(0.020, 0.016)),
                run(4, point(0.020, 0.016), point(0.030, 0.016)));

        assertThat(validator.isValid(
                SixthRingPortal.Direction.OUTBOUND,
                BOUNDARY,
                highwayInterchange,
                interchangeClassification(),
                corridors)).isTrue();
    }

    private static RouteLeg leg(Wgs84Coordinate... geometry) {
        return new RouteLeg(1, 1, List.of(geometry));
    }

    private static Wgs84Coordinate point(double lng, double lat) {
        return new Wgs84Coordinate(lng, lat);
    }

    private static RoadClassificationIndex classification(int highwayBaseEdgeId) {
        BitSet highway = new BitSet();
        highway.set(highwayBaseEdgeId);
        return new RoadClassificationIndex(
                highway, new BitSet(), new BitSet(), "test");
    }

    private static RoadClassificationIndex tollConnectorClassification() {
        BitSet exempt = bits(1);
        BitSet allMainlines = bits(1, 4);
        BitSet sixthRing = bits(1);
        BitSet forbiddenInteriorHighways = bits(4);
        BitSet links = bits(2);
        BitSet sixthInterior = bits(1, 2, 4);
        BitSet exitConnectorKeys = bits(4);
        return new RoadClassificationIndex(
                exempt,
                allMainlines,
                sixthRing,
                new BitSet(),
                forbiddenInteriorHighways,
                links,
                sixthInterior,
                new BitSet(),
                links,
                exitConnectorKeys,
                new BitSet(),
                "toll-test");
    }

    private static RoadClassificationIndex tollCorridorClassification() {
        BitSet exempt = bits(4);
        BitSet allMainlines = bits(4);
        BitSet sixthRing = bits(4);
        BitSet links = bits(3, 5, 6, 7);
        BitSet sixthInterior = bits(2, 3, 7);
        BitSet entryKeys = bits(4, 6);
        BitSet exitKeys = bits(10, 12);
        return new RoadClassificationIndex(
                exempt,
                allMainlines,
                sixthRing,
                new BitSet(),
                new BitSet(),
                links,
                sixthInterior,
                new BitSet(),
                new BitSet(),
                exitKeys,
                entryKeys,
                exitKeys,
                new BitSet(),
                "toll-corridor-test");
    }

    private static RoadClassificationIndex interchangeClassification() {
        BitSet exempt = bits(4, 8);
        BitSet allMainlines = bits(4, 8);
        BitSet sixthRing = bits(4);
        BitSet links = bits(5);
        return new RoadClassificationIndex(
                exempt,
                allMainlines,
                sixthRing,
                new BitSet(),
                new BitSet(),
                links,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                new BitSet(),
                "interchange-test");
    }

    private static TollCorridorTopology tollCorridors() {
        TollCorridorTopology.TollCorridor entry = new TollCorridorTopology.TollCorridor(
                "complex-entry-1",
                "complex",
                TollCorridorTopology.Role.ENTRY,
                1,
                1,
                "entry",
                List.of(4, 6),
                8,
                1);
        TollCorridorTopology.TollCorridor exit = new TollCorridorTopology.TollCorridor(
                "complex-exit-2",
                "complex",
                TollCorridorTopology.Role.EXIT,
                2,
                2,
                "exit",
                List.of(10, 12),
                8,
                1);
        return new TollCorridorTopology(
                List.of(entry, exit),
                bits(4, 6),
                bits(10, 12),
                bits(2, 3, 5, 6),
                new TollCorridorTopology.Audit(2, 2, 2, 1, 1, 0));
    }

    private static BitSet bits(int... values) {
        BitSet result = new BitSet();
        for (int value : values) {
            result.set(value);
        }
        return result;
    }

    @SafeVarargs
    private static List<RouteTracePoint> trace(List<RouteTracePoint>... runs) {
        return java.util.Arrays.stream(runs).flatMap(List::stream).toList();
    }

    private static List<RouteTracePoint> run(
            int baseEdgeId,
            Wgs84Coordinate... points) {
        List<RouteTracePoint> result = new java.util.ArrayList<>();
        for (Wgs84Coordinate point : points) {
            result.add(new RouteTracePoint(
                    result.size(),
                    point,
                    "road",
                    RoadClass.PRIMARY,
                    false,
                    RoadEnvironment.ROAD,
                    baseEdgeId * 2));
        }
        return result;
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
