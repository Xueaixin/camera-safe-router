package cn.camera.safe.camera.update;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.model.CameraUpdateResult;
import cn.camera.safe.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class CameraUpdateScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(CameraUpdateScheduler.class);

    private final AppProperties properties;
    private final CameraUpdateService updateService;

    public CameraUpdateScheduler(AppProperties properties, CameraUpdateService updateService) {
        this.properties = properties;
        this.updateService = updateService;
    }

    @Scheduled(
            cron = "${app.cameras.update.cron:0 15 3 * * *}",
            zone = "${app.cameras.update.zone:Asia/Shanghai}")
    public void runScheduledUpdate() {
        if (!properties.cameras().update().enabled()) {
            return;
        }
        try {
            CameraUpdateResult result = updateService.updateNow();
            LOGGER.info("定时摄像头数据更新完成 状态={} 源SHA256={}",
                    result.status(), result.sourceSha256());
        } catch (BusinessException exception) {
            LOGGER.error("定时摄像头数据更新失败 错误码={} 错误信息={}",
                    exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error("定时摄像头数据更新失败 异常类型={} 错误信息={}",
                    exception.getClass().getName(), exception.getMessage(), exception);
        }
    }
}
