package cn.camera.safe.routing;

public final class SnapshotBuildException extends RuntimeException {
    public SnapshotBuildException(String message) {
        super(message);
    }

    public SnapshotBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
