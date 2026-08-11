package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.EdgeIteratorState;

import java.util.Objects;

final class TimeFirstLegalityWeighting implements Weighting {
    private static final double EFFECTIVE_FORBIDDEN_WEIGHT = 500_000_000;

    private final Weighting delegate;

    TimeFirstLegalityWeighting(Weighting delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public double calcMinWeightPerDistance() {
        return 0;
    }

    @Override
    public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
        double legalityWeight = delegate.calcEdgeWeight(edgeState, reverse);
        if (!Double.isFinite(legalityWeight) || legalityWeight < 0) {
            return Double.POSITIVE_INFINITY;
        }
        long millis = delegate.calcEdgeMillis(edgeState, reverse);
        return millis < 0 || millis == Long.MAX_VALUE
                ? Double.POSITIVE_INFINITY
                : millis / 1_000.0;
    }

    @Override
    public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
        return delegate.calcEdgeMillis(edgeState, reverse);
    }

    @Override
    public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
        double legalityWeight = delegate.calcTurnWeight(inEdge, viaNode, outEdge);
        if (!Double.isFinite(legalityWeight) || legalityWeight < 0
                || legalityWeight >= EFFECTIVE_FORBIDDEN_WEIGHT) {
            return Double.POSITIVE_INFINITY;
        }
        long millis = delegate.calcTurnMillis(inEdge, viaNode, outEdge);
        return millis < 0 || millis == Long.MAX_VALUE
                ? Double.POSITIVE_INFINITY
                : millis / 1_000.0;
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
        return "time-first|" + delegate.getName();
    }
}
