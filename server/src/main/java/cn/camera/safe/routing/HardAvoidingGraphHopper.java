package cn.camera.safe.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.WeightingFactory;

import java.util.Objects;

/** GraphHopper 11 integration that supplies one immutable restriction snapshot per route call. */
public final class HardAvoidingGraphHopper extends GraphHopper {
    private final ThreadLocal<RouteContext> routeContext = new ThreadLocal<>();

    public GHResponse route(GHRequest request, BlockedEdgeSnapshot snapshot, SearchAudit audit) {
        return route(request, snapshot, audit, EdgeTraversalConstraint.ALLOW_ALL);
    }

    public GHResponse route(
            GHRequest request,
            BlockedEdgeSnapshot snapshot,
            SearchAudit audit,
            EdgeTraversalConstraint traversalConstraint) {
        if (routeContext.get() != null) {
            throw new IllegalStateException("不支持嵌套路线调用");
        }
        routeContext.set(new RouteContext(
                Objects.requireNonNull(snapshot),
                Objects.requireNonNull(audit),
                Objects.requireNonNull(traversalConstraint)));
        try {
            return super.route(request);
        } finally {
            routeContext.remove();
        }
    }

    @Override
    protected WeightingFactory createWeightingFactory() {
        WeightingFactory delegate = super.createWeightingFactory();
        RouteContext context = routeContext.get();
        if (context == null) {
            return delegate;
        }
        return (profile, hints, disableTurnCosts) -> {
            var baseWeighting = delegate.createWeighting(profile, hints, disableTurnCosts);
            var constrainedWeighting = context.traversalConstraint() == EdgeTraversalConstraint.ALLOW_ALL
                    ? baseWeighting
                    : new TraversalConstrainedWeighting(
                            baseWeighting, context.traversalConstraint());
            return new BlockedEdgeWeighting(
                    constrainedWeighting,
                    context.snapshot(),
                    context.audit(),
                    getBaseGraph().getEdges());
        };
    }

    private record RouteContext(
            BlockedEdgeSnapshot snapshot,
            SearchAudit audit,
            EdgeTraversalConstraint traversalConstraint) {
    }
}
