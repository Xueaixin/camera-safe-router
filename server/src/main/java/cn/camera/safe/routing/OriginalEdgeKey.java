package cn.camera.safe.routing;

import com.graphhopper.routing.querygraph.VirtualEdgeIteratorState;
import com.graphhopper.util.EdgeIteratorState;

final class OriginalEdgeKey {
    private OriginalEdgeKey() {
    }

    static int resolve(EdgeIteratorState edge, int baseEdgeCount) {
        if (edge.getEdge() < baseEdgeCount) {
            return edge.getEdgeKey();
        }
        EdgeIteratorState detached = edge.detach(false);
        if (!(detached instanceof VirtualEdgeIteratorState virtualEdge)) {
            throw new IllegalStateException(
                    "virtual route edge does not expose its original directed edge key");
        }
        return virtualEdge.getOriginalEdgeKey();
    }

    static int baseEdgeId(EdgeIteratorState edge, int baseEdgeCount) {
        return resolve(edge, baseEdgeCount) / 2;
    }
}
