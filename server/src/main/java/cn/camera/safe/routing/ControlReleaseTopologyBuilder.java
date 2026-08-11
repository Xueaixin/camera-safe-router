package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.storage.BaseGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ControlReleaseTopologyBuilder {

    ControlReleaseTopology build(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            SixthRingPortalTopology boundaryTopology,
            RoadClassificationIndex roadClassification) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(carAccess, "carAccess");
        Objects.requireNonNull(boundaryTopology, "boundaryTopology");
        Objects.requireNonNull(roadClassification, "roadClassification");

        List<ControlReleasePoint> outbound = new ArrayList<>();
        List<ControlReleasePoint> inbound = new ArrayList<>();
        boundaryTopology.outbound().portals().stream()
                .filter(portal -> !roadClassification.isAnyHighwayMainline(portal.edgeId()))
                .filter(portal -> !roadClassification.isMotorwayLink(portal.edgeId()))
                .map(this::ordinaryRelease)
                .forEach(outbound::add);
        boundaryTopology.inbound().portals().stream()
                .filter(portal -> !roadClassification.isAnyHighwayMainline(portal.edgeId()))
                .filter(portal -> !roadClassification.isMotorwayLink(portal.edgeId()))
                .map(this::ordinaryRelease)
                .forEach(inbound::add);

        return new ControlReleaseTopology(outbound, inbound);
    }

    private ControlReleasePoint ordinaryRelease(SixthRingPortal portal) {
        return new ControlReleasePoint(
                "ordinary:" + portal.id(),
                portal.edgeId(),
                portal.edgeKey(),
                portal.direction(),
                ControlReleasePoint.Type.ORDINARY_BOUNDARY,
                portal.fractionFromBase(),
                portal.crossing(),
                portal.roadName(),
                portal);
    }

}
