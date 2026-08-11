package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryCrossingResolverTest {
    private final BoundaryCrossingResolver resolver = new BoundaryCrossingResolver();

    @Test
    void selectsEarliestOutboundAndLatestInboundCrossings() {
        SixthRingPortal outboundFirst = portal(
                "out-first", 0, SixthRingPortal.Direction.OUTBOUND, 1);
        SixthRingPortal outboundLast = portal(
                "out-last", 1, SixthRingPortal.Direction.OUTBOUND, 3);
        SixthRingPortal inboundFirst = portal(
                "in-first", 0, SixthRingPortal.Direction.INBOUND, 1);
        SixthRingPortal inboundLast = portal(
                "in-last", 1, SixthRingPortal.Direction.INBOUND, 3);
        SixthRingPortalTopology topology = topology(
                List.of(outboundFirst, outboundLast),
                List.of(inboundFirst, inboundLast));
        List<RouteTracePoint> trace = List.of(
                trace(0, 0), trace(0, 1),
                trace(2, 2), trace(2, 3));

        assertThat(resolver.resolve(
                SixthRingPortal.Direction.OUTBOUND, topology, trace).id())
                .isEqualTo("out-first");
        assertThat(resolver.resolve(
                SixthRingPortal.Direction.INBOUND, topology, trace).id())
                .isEqualTo("in-last");
    }

    private static SixthRingPortal portal(
            String id,
            int edgeId,
            SixthRingPortal.Direction direction,
            double lng) {
        return new SixthRingPortal(
                id,
                edgeId,
                edgeId * 2,
                direction,
                direction == SixthRingPortal.Direction.OUTBOUND
                        ? SixthRingPortal.BoundaryRole.OUTER_EXIT
                        : SixthRingPortal.BoundaryRole.INNER_ENTRY,
                SixthRingPortal.CandidateType.INTERIOR_EDGE,
                -1,
                0.5,
                new Wgs84Coordinate(lng, 0),
                "road");
    }

    private static RouteTracePoint trace(int edgeKey, double lng) {
        return new RouteTracePoint(
                (int) lng,
                new Wgs84Coordinate(lng, 0),
                "road",
                RoadClass.PRIMARY,
                false,
                RoadEnvironment.ROAD,
                edgeKey);
    }

    private static SixthRingPortalTopology topology(
            List<SixthRingPortal> outbound,
            List<SixthRingPortal> inbound) {
        return new SixthRingPortalTopology(
                "test",
                new SixthRingPortalTopology.Scan(
                        SixthRingPortal.BoundaryRole.OUTER_EXIT,
                        SixthRingPortal.Direction.OUTBOUND,
                        0, 0, 0, 0, 0, 0, outbound),
                new SixthRingPortalTopology.Scan(
                        SixthRingPortal.BoundaryRole.INNER_ENTRY,
                        SixthRingPortal.Direction.INBOUND,
                        0, 0, 0, 0, 0, 0, inbound));
    }
}
