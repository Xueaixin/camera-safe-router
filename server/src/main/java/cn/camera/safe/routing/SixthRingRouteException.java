package cn.camera.safe.routing;

public final class SixthRingRouteException extends RuntimeException {
    public enum Reason {
        TOPOLOGY_NOT_READY,
        BOUNDARY_AMBIGUOUS,
        POINT_NOT_FOUND,
        NO_ROUTE,
        SEARCH_TIMEOUT,
        RESOURCE_LIMIT,
        REFERENCE_ROUTE_FAILED
    }

    private final Reason reason;

    public SixthRingRouteException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SixthRingRouteException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
