package cn.camera.safe.routing;

import java.util.List;

record ControlReleaseTopology(
        List<ControlReleasePoint> outbound,
        List<ControlReleasePoint> inbound) {

    ControlReleaseTopology {
        outbound = List.copyOf(outbound);
        inbound = List.copyOf(inbound);
        if (outbound.stream().anyMatch(point ->
                point.direction() != SixthRingPortal.Direction.OUTBOUND)
                || inbound.stream().anyMatch(point ->
                point.direction() != SixthRingPortal.Direction.INBOUND)) {
            throw new IllegalArgumentException("control release direction does not match its scan");
        }
    }
}
