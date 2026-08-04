package cn.camera.safe.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static cn.camera.safe.routing.SixthRingPortal.Direction.INBOUND;
import static cn.camera.safe.routing.SixthRingPortal.Direction.OUTBOUND;

public record SixthRingPortalTopology(
        String boundaryVersion,
        Scan outbound,
        Scan inbound) {

    public SixthRingPortalTopology {
        if (boundaryVersion == null || boundaryVersion.isBlank()) {
            throw new IllegalArgumentException("boundary version is required");
        }
        Objects.requireNonNull(outbound, "outbound");
        Objects.requireNonNull(inbound, "inbound");
        if (outbound.direction() != OUTBOUND || inbound.direction() != INBOUND) {
            throw new IllegalArgumentException("portal scans use the wrong direction");
        }
    }

    public List<SixthRingPortal> allPortals() {
        List<SixthRingPortal> result = new ArrayList<>(
                outbound.portals().size() + inbound.portals().size());
        result.addAll(outbound.portals());
        result.addAll(inbound.portals());
        return List.copyOf(result);
    }

    public record Scan(
            SixthRingPortal.BoundaryRole boundaryRole,
            SixthRingPortal.Direction direction,
            int intersectingEdges,
            int overlappingEdges,
            int ambiguousEdges,
            int geometryCrossings,
            int boundaryNodes,
            int nodeTransitionCandidates,
            List<SixthRingPortal> portals) {

        public Scan {
            Objects.requireNonNull(boundaryRole, "boundaryRole");
            Objects.requireNonNull(direction, "direction");
            portals = List.copyOf(portals);
            if (intersectingEdges < 0 || overlappingEdges < 0 || ambiguousEdges < 0
                    || geometryCrossings < 0 || boundaryNodes < 0
                    || nodeTransitionCandidates < 0) {
                throw new IllegalArgumentException("portal scan counts must be non-negative");
            }
            if (portals.stream().anyMatch(portal -> portal.direction() != direction
                    || portal.boundaryRole() != boundaryRole)) {
                throw new IllegalArgumentException("portal does not belong to its scan");
            }
        }
    }
}
