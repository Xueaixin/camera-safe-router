package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.AbstractAdjustedWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;

import java.util.Objects;

/** Rejects traversals outside a route-mode-specific boundary during search. */
public final class TraversalConstrainedWeighting extends AbstractAdjustedWeighting {
    private final EdgeTraversalConstraint constraint;

    public TraversalConstrainedWeighting(
            Weighting delegate,
            EdgeTraversalConstraint constraint) {
        super(Objects.requireNonNull(delegate));
        this.constraint = Objects.requireNonNull(constraint);
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        if (!constraint.allows(edgeState)) {
            return Double.POSITIVE_INFINITY;
        }
        return super.calcEdgeWeight(edgeState, reverse);
    }

    @Override
    public String getName() {
        return "boundary_constrained|" + superWeighting.getName();
    }
}
