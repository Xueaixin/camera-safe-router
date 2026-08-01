package cn.camera.safe.application;

import cn.camera.safe.config.ExecutorConfiguration;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

@Component
public final class BackendInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendInitializer.class);

    private final GraphHopperManager graphManager;
    private final RoutingSnapshotManager snapshotManager;
    private final ExecutorService executor;

    public BackendInitializer(
            GraphHopperManager graphManager,
            RoutingSnapshotManager snapshotManager,
            @Qualifier(ExecutorConfiguration.INITIALIZATION_EXECUTOR) ExecutorService executor) {
        this.graphManager = graphManager;
        this.snapshotManager = snapshotManager;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterServerStarts() {
        executor.execute(() -> {
            try {
                graphManager.initialize();
                snapshotManager.refreshNow();
            } catch (RuntimeException exception) {
                LOGGER.error("后端初始化未完成 异常类型={} 错误信息={}",
                        exception.getClass().getName(), exception.getMessage());
            }
        });
    }
}
