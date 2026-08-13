package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.storage.BaseGraph;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ControlReleaseTopologyBuilder {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlReleaseTopologyBuilder.class);

    ControlReleaseTopology build(
            BaseGraph graph,
            BooleanEncodedValue carAccess,
            SixthRingPortalTopology boundaryTopology,
            RoadClassificationIndex roadClassification,
            Geometry provincialBorder,
            double provincialBorderToleranceMeters) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(carAccess, "carAccess");
        Objects.requireNonNull(boundaryTopology, "boundaryTopology");
        Objects.requireNonNull(roadClassification, "roadClassification");
        if (!Double.isFinite(provincialBorderToleranceMeters)
                || provincialBorderToleranceMeters < 0) {
            throw new IllegalArgumentException(
                    "provincial border tolerance must be non-negative");
        }

        List<ControlReleasePoint> outbound = new ArrayList<>();
        List<ControlReleasePoint> inbound = new ArrayList<>();
        boundaryTopology.outbound().portals().stream()
                .filter(portal -> !roadClassification.isAnyHighwayMainline(portal.edgeId()))
                .filter(portal -> !roadClassification.isMotorwayLink(portal.edgeId()))
                .map(this::ordinaryRelease)
                .forEach(outbound::add);
        int inboundOrdinaryTotal = 0;
        int inboundProvincialExcluded = 0;
        for (SixthRingPortal portal : boundaryTopology.inbound().portals()) {
            if (roadClassification.isAnyHighwayMainline(portal.edgeId())
                    || roadClassification.isMotorwayLink(portal.edgeId())) {
                continue;
            }
            inboundOrdinaryTotal++;
            if (onProvincialBorder(portal, provincialBorder, provincialBorderToleranceMeters)) {
                inboundProvincialExcluded++;
                continue;
            }
            inbound.add(ordinaryRelease(portal));
        }
        LOGGER.info("控制释放点拓扑 入界普通口总数={} 省界禁行排除={} 保留={}",
                inboundOrdinaryTotal, inboundProvincialExcluded, inbound.size());

        return new ControlReleaseTopology(outbound, inbound);
    }

    static boolean onProvincialBorder(
            SixthRingPortal portal,
            Geometry provincialBorder,
            double toleranceMeters) {
        if (portal == null || provincialBorder == null || provincialBorder.isEmpty()
                || toleranceMeters <= 0) {
            return false;
        }
        double toleranceDegrees = toleranceMeters / 111_320.0;
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(
                portal.crossing().lng(), portal.crossing().lat()));
        return provincialBorder.isWithinDistance(point, toleranceDegrees);
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
