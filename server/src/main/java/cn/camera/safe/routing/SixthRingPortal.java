package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

import java.util.Objects;

public record SixthRingPortal(
        String id,
        int edgeId,
        int edgeKey,
        Direction direction,
        BoundaryRole boundaryRole,
        CandidateType candidateType,
        int boundaryNode,
        double fractionFromBase,
        Wgs84Coordinate crossing,
        String roadName) {

    public SixthRingPortal {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("portal id is required");
        }
        if (edgeId < 0 || edgeKey < 0) {
            throw new IllegalArgumentException("portal edge identifiers must be non-negative");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(boundaryRole, "boundaryRole");
        Objects.requireNonNull(candidateType, "candidateType");
        Objects.requireNonNull(crossing, "crossing");
        if (!Double.isFinite(fractionFromBase)
                || fractionFromBase < 0 || fractionFromBase > 1) {
            throw new IllegalArgumentException("portal fraction must be within [0, 1]");
        }
        roadName = roadName == null ? "" : roadName;
    }

    public enum Direction {
        OUTBOUND,
        INBOUND
    }

    public enum BoundaryRole {
        OUTER_EXIT,
        INNER_ENTRY
    }

    public enum CandidateType {
        INTERIOR_EDGE,
        OVERLAP_EDGE_EXIT,
        BOUNDARY_NODE_TRANSITION,
        BOUNDARY_CHAIN_TRANSITION
    }
}
