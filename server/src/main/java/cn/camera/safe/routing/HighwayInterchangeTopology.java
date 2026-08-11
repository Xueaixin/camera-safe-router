package cn.camera.safe.routing;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Verified non-toll highway interchanges connecting the sixth-ring mainline
 * {@code R} to the Tongzhou outside-sixth allowed highway mainline {@code H-T}.
 * Each corridor is a complete directed link chain, proof that the route can move
 * between the two mainlines without leaving the interchange.
 */
record HighwayInterchangeTopology(
        List<InterchangeCorridor> corridors,
        BitSet rToHtEdgeKeys,
        BitSet htToREdgeKeys,
        Audit audit) {

    HighwayInterchangeTopology {
        corridors = List.copyOf(Objects.requireNonNull(corridors, "corridors"));
        rToHtEdgeKeys = copy(rToHtEdgeKeys, "rToHtEdgeKeys");
        htToREdgeKeys = copy(htToREdgeKeys, "htToREdgeKeys");
        Objects.requireNonNull(audit, "audit");
    }

    static HighwayInterchangeTopology empty() {
        return new HighwayInterchangeTopology(
                List.of(), new BitSet(), new BitSet(),
                new Audit(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    record InterchangeCorridor(
            String id,
            String complexId,
            Role role,
            List<Integer> directedEdgeKeys,
            int rMainlineEdgeKey,
            int htMainlineEdgeKey,
            double distanceMeters,
            boolean touchesSixthInterior) {
        InterchangeCorridor {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(complexId, "complexId");
            Objects.requireNonNull(role, "role");
            directedEdgeKeys = List.copyOf(Objects.requireNonNull(
                    directedEdgeKeys, "directedEdgeKeys"));
        }
    }

    enum Role {
        /** Sixth-ring mainline {@code R} to Tongzhou allowed highway {@code H-T}. */
        R_TO_HT,
        /** Tongzhou allowed highway {@code H-T} to sixth-ring mainline {@code R}. */
        HT_TO_R
    }

    record Audit(
            int linkComponents,
            int componentsTouchingRing,
            int componentsTouchingHT,
            int candidateComponents,
            int rToHtCorridors,
            int htToRCorridors,
            int unresolvedComponents,
            int excludedTollNodeChains,
            int excludedTollCorridorOverlapChains) {
    }

    private static BitSet copy(BitSet source, String name) {
        return (BitSet) Objects.requireNonNull(source, name).clone();
    }
}
