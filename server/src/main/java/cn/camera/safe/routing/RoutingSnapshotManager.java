package cn.camera.safe.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;
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
        return runExclusively(() -> publishLocked(builder.build(), () -> {
        }));
    }

    public RoutingSnapshot publishCandidate(
            RoutingSnapshot candidate,
            SourceActivation sourceActivation) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(sourceActivation);
        return runExclusively(() -> publishLocked(candidate, sourceActivation));
    }

    private RoutingSnapshot runExclusively(SnapshotOperation operation) {
        if (!refreshLock.tryLock()) {
            throw new RefreshAlreadyRunningException();
        }
        try {
            return operation.run();
        } catch (IOException | RuntimeException exception) {
            lastFailure = exception.getMessage();
            LOGGER.error("路由快照刷新失败 异常类型={} 错误信息={} 已保留旧快照={}",
                    exception.getClass().getName(), exception.getMessage(), current.get() != null);
            throw new SnapshotBuildException("路由快照刷新失败", exception);
        } finally {
            refreshLock.unlock();
        }
    }

    private RoutingSnapshot publishLocked(
            RoutingSnapshot candidate,
            SourceActivation sourceActivation) throws IOException {
        store.persist(candidate);
        sourceActivation.activate();
        current.set(candidate);
        lastFailure = null;
        LOGGER.info("路由快照发布完成 源摄像头数={} 保留摄像头数={} "
                        + "旧六环外排除数={} 无法识别IsSixRingOut数={} 受控摄像头数={} "
                        + "受控区外解禁数={} 边界版本={} 界外边距米={} 禁行边数={} "
                        + "匹配数={} 仅命中高速豁免边数={} 未匹配数={}",
                candidate.cameraSnapshot().sourceRecordCount(),
                candidate.cameraSnapshot().retainedRecordCount(),
                candidate.cameraSnapshot().outsideSixRingRecordCount(),
                candidate.cameraSnapshot().unrecognizedSixRingOutRecordCount(),
                candidate.restrictedCameraCount(),
                candidate.outsideControlAreaCameraCount(),
                candidate.controlBoundaryVersion(),
                candidate.cameraOutsideMarginMeters(),
                candidate.blockedEdges().blockedEdgeCount(),
                candidate.matchedCameraCount(),
                candidate.highwayExemptCameraCount(),
                candidate.unmatchedCameraIds().size());
        try {
            store.prune();
        } catch (IOException exception) {
            LOGGER.warn("路由快照历史清理失败 错误信息={}", exception.getMessage());
        }
        return candidate;
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
            super("摄像头快照刷新正在执行");
        }
    }

    @FunctionalInterface
    public interface SourceActivation {
        void activate() throws IOException;
    }

    @FunctionalInterface
    private interface SnapshotOperation {
        RoutingSnapshot run() throws IOException;
    }
}
