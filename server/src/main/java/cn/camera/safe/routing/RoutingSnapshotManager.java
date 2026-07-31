package cn.camera.safe.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Component
public final class RoutingSnapshotManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingSnapshotManager.class);

    private final RoutingSnapshotBuilder builder;
    private final RoutingSnapshotStore store;
    private final AtomicReference<RoutingSnapshot> current = new AtomicReference<>();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private volatile String lastFailure;

    public RoutingSnapshotManager(RoutingSnapshotBuilder builder, RoutingSnapshotStore store) {
        this.builder = builder;
        this.store = store;
    }

    public RoutingSnapshot refreshNow() {
        if (!refreshLock.tryLock()) {
            throw new RefreshAlreadyRunningException();
        }
        try {
            RoutingSnapshot candidate = builder.build();
            store.persist(candidate);
            current.set(candidate);
            lastFailure = null;
            LOGGER.info("Published routing snapshot sourceCameras={} retainedCameras={} "
                            + "outsideSixRing={} unrecognizedIsSixRingOut={} blockedEdges={} matched={} unmatched={}",
                    candidate.cameraSnapshot().sourceRecordCount(),
                    candidate.cameraSnapshot().retainedRecordCount(),
                    candidate.cameraSnapshot().outsideSixRingRecordCount(),
                    candidate.cameraSnapshot().unrecognizedSixRingOutRecordCount(),
                    candidate.blockedEdges().blockedEdgeCount(),
                    candidate.matchedCameraCount(),
                    candidate.unmatchedCameraIds().size());
            return candidate;
        } catch (IOException | RuntimeException exception) {
            lastFailure = exception.getMessage();
            LOGGER.error("Routing snapshot refresh failed type={} message={}; previous snapshot retained={}",
                    exception.getClass().getName(), exception.getMessage(), current.get() != null);
            throw new SnapshotBuildException("routing snapshot refresh failed", exception);
        } finally {
            refreshLock.unlock();
        }
    }

    public Optional<RoutingSnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    public boolean isReady() {
        return current.get() != null;
    }

    public boolean isRefreshRunning() {
        return refreshLock.isLocked();
    }

    public String lastFailure() {
        return lastFailure;
    }

    public static final class RefreshAlreadyRunningException extends RuntimeException {
        public RefreshAlreadyRunningException() {
            super("camera snapshot refresh is already running");
        }
    }
}
