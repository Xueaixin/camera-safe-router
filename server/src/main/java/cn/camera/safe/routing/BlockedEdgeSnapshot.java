package cn.camera.safe.routing;

import com.graphhopper.util.GHUtility;

import java.util.BitSet;
import java.util.Collection;
import java.util.Objects;

/** Immutable, graph-build-specific directed edge restrictions. */
public final class BlockedEdgeSnapshot {
    private static final BlockedEdgeSnapshot EMPTY = new BlockedEdgeSnapshot(
            new BitSet(), new BitSet(), "none", "none");

    private final BitSet blockedForward;
    private final BitSet blockedReverse;
    private final String cameraSnapshotVersion;
    private final String blockedEdgeVersion;

    public BlockedEdgeSnapshot(
            BitSet blockedForward,
            BitSet blockedReverse,
            String cameraSnapshotVersion,
            String blockedEdgeVersion) {
        this.blockedForward = (BitSet) Objects.requireNonNull(blockedForward).clone();
        this.blockedReverse = (BitSet) Objects.requireNonNull(blockedReverse).clone();
        this.cameraSnapshotVersion = Objects.requireNonNull(cameraSnapshotVersion);
        this.blockedEdgeVersion = Objects.requireNonNull(blockedEdgeVersion);
    }

    public static BlockedEdgeSnapshot empty() {
        return EMPTY;
    }

    public static BlockedEdgeSnapshot blockBothDirections(Collection<Integer> edgeIds, String version) {
        BitSet forward = new BitSet();
        BitSet reverse = new BitSet();
        edgeIds.forEach(edgeId -> {
            forward.set(edgeId);
            reverse.set(edgeId);
        });
        return new BlockedEdgeSnapshot(forward, reverse, version, version);
    }

    public boolean isBlockedEdgeKey(int originalEdgeKey) {
        int edgeId = GHUtility.getEdgeFromEdgeKey(originalEdgeKey);
        return (originalEdgeKey & 1) == 0
                ? blockedForward.get(edgeId)
                : blockedReverse.get(edgeId);
    }

    public int blockedEdgeCount() {
        BitSet union = (BitSet) blockedForward.clone();
        union.or(blockedReverse);
        return union.cardinality();
    }

    public int blockedForwardCount() {
        return blockedForward.cardinality();
    }

    public int blockedReverseCount() {
        return blockedReverse.cardinality();
    }

    public BitSet blockedForward() {
        return (BitSet) blockedForward.clone();
    }

    public BitSet blockedReverse() {
        return (BitSet) blockedReverse.clone();
    }

    public String cameraSnapshotVersion() {
        return cameraSnapshotVersion;
    }

    public String blockedEdgeVersion() {
        return blockedEdgeVersion;
    }
}
