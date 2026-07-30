package cn.camera.safe.routing;

import java.util.concurrent.atomic.LongAdder;

/** Request-local evidence that restrictions were evaluated by the route search. */
public final class SearchAudit {
    private final LongAdder edgeChecks = new LongAdder();
    private final LongAdder virtualEdgeChecks = new LongAdder();
    private final LongAdder blockedRejections = new LongAdder();

    void recordCheck(boolean virtual) {
        edgeChecks.increment();
        if (virtual) {
            virtualEdgeChecks.increment();
        }
    }

    void recordBlockedRejection() {
        blockedRejections.increment();
    }

    public long edgeChecks() {
        return edgeChecks.sum();
    }

    public long virtualEdgeChecks() {
        return virtualEdgeChecks.sum();
    }

    public long blockedRejections() {
        return blockedRejections.sum();
    }
}
