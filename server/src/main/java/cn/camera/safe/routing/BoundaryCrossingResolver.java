package cn.camera.safe.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.linearref.LengthIndexedLine;

/** Resolves the user-visible physical crossing from the final directed edge trace. */
final class BoundaryCrossingResolver {
    private static final double EDGE_MATCH_TOLERANCE_METERS = 2;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    SixthRingPortal resolve(
            SixthRingPortal.Direction direction,
            SixthRingPortalTopology topology,
            List<RouteTracePoint> actualDirectionTrace) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(topology, "topology");
        List<SixthRingPortal> portals = direction == SixthRingPortal.Direction.OUTBOUND
                ? topology.outbound().portals()
                : topology.inbound().portals();
        Map<Integer, List<SixthRingPortal>> byEdgeKey = new HashMap<>();
        for (SixthRingPortal portal : portals) {
            byEdgeKey.computeIfAbsent(portal.edgeKey(), ignored -> new ArrayList<>())
                    .add(portal);
        }
        List<SixthRingPortal> encountered = new ArrayList<>();
        for (RouteTraceSupport.EdgeRun run : RouteTraceSupport.edgeRuns(actualDirectionTrace)) {
            List<SixthRingPortal> candidates = byEdgeKey.get(run.originalEdgeKey());
            if (candidates == null || run.geometry().size() < 2) {
                continue;
            }
            LengthIndexedLine indexed = indexed(run);
            candidates.stream()
                    .filter(portal -> GeoDistance.minimumMeters(
                            portal.crossing(), run.geometry()) <= EDGE_MATCH_TOLERANCE_METERS)
                    .map(portal -> new Encounter(
                            portal,
                            indexed.project(new Coordinate(
                                    portal.crossing().lng(), portal.crossing().lat()))))
                    .sorted(java.util.Comparator.comparingDouble(Encounter::routeIndex))
                    .map(Encounter::portal)
                    .forEach(encountered::add);
        }
        if (encountered.isEmpty()) {
            throw new IllegalStateException(
                    "final route trace does not contain a directed physical boundary crossing");
        }
        return direction == SixthRingPortal.Direction.OUTBOUND
                ? encountered.getFirst()
                : encountered.getLast();
    }

    private static LengthIndexedLine indexed(RouteTraceSupport.EdgeRun run) {
        Coordinate[] coordinates = run.geometry().stream()
                .map(point -> new Coordinate(point.lng(), point.lat()))
                .toArray(Coordinate[]::new);
        return new LengthIndexedLine(GEOMETRY_FACTORY.createLineString(coordinates));
    }

    private record Encounter(SixthRingPortal portal, double routeIndex) {
    }
}
