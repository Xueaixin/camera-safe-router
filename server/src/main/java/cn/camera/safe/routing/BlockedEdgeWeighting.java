package cn.camera.safe.routing;

import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;

import java.util.Objects;

/** Returns infinite weight for a blocked traversal while the routing algorithm expands edges. */
public final class BlockedEdgeWeighting extends AbstractAdjustedWeighting {
    private final BlockedEdgeSnapshot snapshot;
    private final SearchAudit audit;
    private final int baseEdgeCount;

    public BlockedEdgeWeighting(
            Weighting delegate,
            BlockedEdgeSnapshot snapshot,
            SearchAudit audit,
            int baseEdgeCount) {
        super(Objects.requireNonNull(delegate));
        this.snapshot = Objects.requireNonNull(snapshot);
        this.audit = Objects.requireNonNull(audit);
        if (baseEdgeCount < 0) {
            throw new IllegalArgumentException("baseEdgeCount must not be negative");
        }
        this.baseEdgeCount = baseEdgeCount;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        boolean virtual = edgeState.getEdge() >= baseEdgeCount;
        audit.recordCheck(virtual);

        int orientedOriginalKey;
        if (virtual) {
            EdgeIteratorState detached = edgeState.detach(false);
            if (!(detached instanceof VirtualEdgeIteratorState virtualEdge)) {
                throw new IllegalStateException(
                        "GraphHopper 虚拟边未提供 VirtualEdgeIteratorState: "
                                + detached.getClass().getName());
            }
            orientedOriginalKey = virtualEdge.getOriginalEdgeKey();
        } else {
            orientedOriginalKey = edgeState.getEdgeKey();
        }
        int traversalOriginalKey = reverse
                ? GHUtility.reverseEdgeKey(orientedOriginalKey)
                : orientedOriginalKey;

        if (snapshot.isBlockedEdgeKey(traversalOriginalKey)) {
            audit.recordBlockedRejection();
            return Double.POSITIVE_INFINITY;
        }
        return super.calcEdgeWeight(edgeState, reverse);
    }

    @Override
    public String getName() {
        return "hard_blocked_edges|" + superWeighting.getName();
    }
}
