package cn.camera.safe.camera.update;

import cn.camera.safe.api.model.CameraUpdateResult;
import cn.camera.safe.api.model.CameraUpdateStatus;
import cn.camera.safe.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CameraUpdateSchedulerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void runsOnlyWhenInternalSchedulingIsEnabled() {
        CameraUpdateService service = mock(CameraUpdateService.class);
        AppProperties enabled = CameraUpdateTestSupport.properties(
                temporaryDirectory, "https://example.test/cameras");
        when(service.updateNow()).thenReturn(new CameraUpdateResult(
                CameraUpdateStatus.NO_CHANGE,
                "hash",
                "hash",
                1,
                1,
                0,
                0,
                1,
                0,
                1,
                Instant.EPOCH));

        new CameraUpdateScheduler(disabled(enabled), service).runScheduledUpdate();
        verify(service, never()).updateNow();

        new CameraUpdateScheduler(enabled, service).runScheduledUpdate();
        verify(service).updateNow();
    }

    private static AppProperties disabled(AppProperties enabled) {
        AppProperties.Update value = enabled.cameras().update();
        AppProperties.Update disabled = new AppProperties.Update(
                false,
                value.sourceUrl(),
                value.cron(),
                value.zone(),
                value.downloadPath(),
                value.failedPath(),
                value.backupPath(),
                value.connectTimeout(),
                value.requestTimeout(),
                value.maxDownloadBytes(),
                value.minSourceRecordCount(),
                value.maxSourceCountChangeRatio(),
                value.minMatchRate(),
                value.maxBlockedEdgeChangeRatio(),
                value.backupRetentionCount(),
                value.snapshotRetentionCount(),
                value.failedRetentionCount());
        return new AppProperties(
                enabled.routing(),
                new AppProperties.Cameras(
                        enabled.cameras().jsonPath(),
                        enabled.cameras().snapshotPath(),
                        enabled.cameras().sourceCoordinateVerified(),
                        enabled.cameras().maxBboxResults(),
                        disabled),
                enabled.admin());
    }
}
