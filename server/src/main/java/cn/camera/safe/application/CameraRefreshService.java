package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.config.ExecutorConfiguration;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public final class CameraRefreshService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CameraRefreshService.class);

    private final GraphHopperManager graphManager;
    private final RoutingSnapshotManager snapshotManager;
    private final ExecutorService executor;
    private final AtomicBoolean acceptedOrRunning = new AtomicBoolean();

    public CameraRefreshService(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            @Qualifier(ExecutorConfiguration.REFRESH_EXECUTOR) ExecutorService executor) {
        this.graphManager = graphManager;
        this.snapshotManager = snapshotManager;
        this.executor = executor;
    }

    public String requestRefresh() {
        if (!graphManager.isReady()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路网尚未就绪");
        }
        if (snapshotManager.isRefreshRunning() || !acceptedOrRunning.compareAndSet(false, true)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.REFRESH_ALREADY_RUNNING, "摄像头快照刷新正在进行");
        }
        String jobId = UUID.randomUUID().toString();
        try {
            executor.execute(() -> {
                try {
                    snapshotManager.refreshNow();
                    LOGGER.info("摄像头快照刷新完成 任务ID={}", jobId);
                } catch (RuntimeException exception) {
                    LOGGER.error("摄像头快照刷新失败 任务ID={} 异常类型={} 错误信息={}",
                            jobId, exception.getClass().getName(), exception.getMessage());
                } finally {
                    acceptedOrRunning.set(false);
                }
            });
            return jobId;
        } catch (RejectedExecutionException exception) {
            acceptedOrRunning.set(false);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "刷新执行器不可用");
        }
    }

    public boolean isRunning() {
        return acceptedOrRunning.get();
    }
}
