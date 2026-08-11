package cn.camera.safe.routing;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

record TollCorridorTopology(
        List<TollCorridor> corridors,
        BitSet entryEdgeKeys,
        BitSet exitEdgeKeys,
        BitSet baseEdges,
        Audit audit) {

    TollCorridorTopology {
        corridors = List.copyOf(Objects.requireNonNull(corridors, "corridors"));
        entryEdgeKeys = copy(entryEdgeKeys, "entryEdgeKeys");
        exitEdgeKeys = copy(exitEdgeKeys, "exitEdgeKeys");
        baseEdges = copy(baseEdges, "baseEdges");
        Objects.requireNonNull(audit, "audit");
    }

    static TollCorridorTopology empty(int sourceTollBooths) {
        return new TollCorridorTopology(
                List.of(), new BitSet(), new BitSet(), new BitSet(),
                new Audit(sourceTollBooths, 0, 0, 0, 0, 0));
    }

    record TollCorridor(
            String id,
            String complexId,
            Role role,
            long tollNodeId,
            int graphNode,
            String name,
            List<Integer> directedEdgeKeys,
            int sixthRingMainlineEdgeKey,
            double distanceMeters) {
        TollCorridor {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(complexId, "complexId");
            Objects.requireNonNull(role, "role");
            directedEdgeKeys = List.copyOf(Objects.requireNonNull(
                    directedEdgeKeys, "directedEdgeKeys"));
        }
    }

    enum Role {
        ENTRY,
        EXIT
    }

    record Audit(
            int sourceTollBooths,
            int mappedTollBooths,
            int sixthRingRelatedTollBooths,
            int entryCorridors,
            int exitCorridors,
            int unresolvedRelatedTollBooths) {
    }

    private static BitSet copy(BitSet source, String name) {
        return (BitSet) Objects.requireNonNull(source, name).clone();
    }
}
