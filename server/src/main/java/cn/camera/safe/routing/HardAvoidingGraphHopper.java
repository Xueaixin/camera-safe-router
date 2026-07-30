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
        if (routeContext.get() != null) {
            throw new IllegalStateException("Nested route calls are not supported");
        }
        routeContext.set(new RouteContext(Objects.requireNonNull(snapshot), Objects.requireNonNull(audit)));
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
        return (profile, hints, disableTurnCosts) -> new BlockedEdgeWeighting(
                delegate.createWeighting(profile, hints, disableTurnCosts),
                context.snapshot(),
                context.audit(),
                getBaseGraph().getEdges());
    }

    private record RouteContext(BlockedEdgeSnapshot snapshot, SearchAudit audit) {
    }
}
