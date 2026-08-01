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
            LOGGER.info("Scheduled camera update completed status={} sourceSha256={}",
                    result.status(), result.sourceSha256());
        } catch (BusinessException exception) {
            LOGGER.error("Scheduled camera update failed code={} message={}",
                    exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.error("Scheduled camera update failed type={} message={}",
                    exception.getClass().getName(), exception.getMessage(), exception);
        }
    }
}
