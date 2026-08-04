package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;

import java.util.Objects;

final class DistanceFirstLegalityWeighting implements Weighting {
    private final Weighting delegate;

    DistanceFirstLegalityWeighting(Weighting delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public double calcMinWeightPerDistance() {
        return 1;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        return Double.isFinite(delegate.calcEdgeWeight(edgeState, reverse))
                ? edgeState.getDistance()
                : Double.POSITIVE_INFINITY;
    }

    @Override
    public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
        return delegate.calcEdgeMillis(edgeState, reverse);
    }

    @Override
    public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
        return delegate.calcTurnWeight(inEdge, viaNode, outEdge);
    }

    @Override
    public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
        return delegate.calcTurnMillis(inEdge, viaNode, outEdge);
    }

    @Override
    public boolean hasTurnCosts() {
        return delegate.hasTurnCosts();
    }

    @Override
    public String getName() {
        return "distance-first|" + delegate.getName();
    }
}
