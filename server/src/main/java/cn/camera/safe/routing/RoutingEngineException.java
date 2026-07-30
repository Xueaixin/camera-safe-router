package cn.camera.safe.routing;

public final class RoutingEngineException extends RuntimeException {
    public enum Reason {
        POINT_NOT_FOUND,
        NO_ROUTE
    }

    private final Reason reason;

    public RoutingEngineException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
