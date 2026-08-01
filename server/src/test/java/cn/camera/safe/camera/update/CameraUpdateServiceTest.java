package cn.camera.safe.camera.update;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.CameraUpdateStatus;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.Hashing;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotBuilder;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CameraUpdateServiceTest {
    @TempDir
    Path temporaryDirectory;

    private GraphHopperManager graphManager;
    private RoutingSnapshotBuilder snapshotBuilder;
    private RoutingSnapshotManager snapshotManager;
    private CameraSourceClient sourceClient;
    private CameraUpdateFileStore fileStore;
    private CameraUpdateService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = CameraUpdateTestSupport.properties(
                temporaryDirectory, "https://example.test/cameras");
        graphManager = mock(GraphHopperManager.class);
        snapshotBuilder = mock(RoutingSnapshotBuilder.class);
        snapshotManager = mock(RoutingSnapshotManager.class);
        sourceClient = mock(CameraSourceClient.class);
        fileStore = mock(CameraUpdateFileStore.class);
        service = new CameraUpdateService(
                properties, graphManager, snapshotBuilder, snapshotManager, sourceClient, fileStore);
        when(graphManager.isReady()).thenReturn(true);
    }

    @Test
    void publishesAValidatedCandidateAndReturnsCounts() throws Exception {
        byte[] content = "new-source".getBytes();
        String newHash = Hashing.sha256(content);
        RoutingSnapshot previous = CameraUpdateTestSupport.snapshot("a".repeat(64), 1, 1);
        RoutingSnapshot candidate = CameraUpdateTestSupport.snapshot(newHash, 1, 1);
        Path staged = temporaryDirectory.resolve("staged.json");
        Path prepared = temporaryDirectory.resolve("prepared.json");
        when(sourceClient.download()).thenReturn(new DownloadedCameraSource(content, newHash));
        when(snapshotManager.current()).thenReturn(Optional.of(previous));
        when(fileStore.currentSha256()).thenReturn(Optional.of(previous.cameraSnapshot().sourceSha256()));
        when(fileStore.stage(any())).thenReturn(staged);
        when(snapshotBuilder.build(staged)).thenReturn(candidate);
        when(fileStore.prepareForPublication(staged)).thenReturn(prepared);
        doAnswer(invocation -> {
            RoutingSnapshotManager.SourceActivation activation = invocation.getArgument(1);
            activation.activate();
            return candidate;
        }).when(snapshotManager).publishCandidate(eq(candidate), any());

        var result = service.updateNow();

        assertThat(result.status()).isEqualTo(CameraUpdateStatus.UPDATED);
        assertThat(result.sourceSha256()).isEqualTo(newHash);
        assertThat(result.previousSourceSha256()).isEqualTo(previous.cameraSnapshot().sourceSha256());
        verify(fileStore).activate(prepared);
        verify(fileStore).cleanupAfterSuccess(staged);
    }

    @Test
    void returnsNoChangeWithoutBuildingOrWriting() throws Exception {
        byte[] content = "same-source".getBytes();
        String hash = Hashing.sha256(content);
        RoutingSnapshot current = CameraUpdateTestSupport.snapshot(hash, 1, 1);
        when(sourceClient.download()).thenReturn(new DownloadedCameraSource(content, hash));
        when(snapshotManager.current()).thenReturn(Optional.of(current));
        when(fileStore.currentSha256()).thenReturn(Optional.of(hash));

        var result = service.updateNow();

        assertThat(result.status()).isEqualTo(CameraUpdateStatus.NO_CHANGE);
        verify(fileStore, never()).stage(any());
        verify(snapshotBuilder, never()).build(any(Path.class));
    }

    @Test
    void rejectsLowMatchRateAndQuarantinesTheCandidate() throws Exception {
        byte[] content = "bad-source".getBytes();
        String hash = Hashing.sha256(content);
        RoutingSnapshot candidate = CameraUpdateTestSupport.snapshot(hash, 0, 0);
        Path staged = temporaryDirectory.resolve("staged.json");
        when(sourceClient.download()).thenReturn(new DownloadedCameraSource(content, hash));
        when(snapshotManager.current()).thenReturn(Optional.empty());
        when(fileStore.currentSha256()).thenReturn(Optional.empty());
        when(fileStore.stage(any())).thenReturn(staged);
        when(snapshotBuilder.build(staged)).thenReturn(candidate);

        assertThatThrownBy(service::updateNow)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.CAMERA_UPDATE_FAILED);
                    assertThat(exception.details().get("reason").toString()).contains("匹配率");
                });
        verify(fileStore).quarantine(staged);
        verify(snapshotManager, never()).publishCandidate(any(), any());
    }

    @Test
    void rejectsASecondUpdateWhileTheFirstOneIsRunning() throws Exception {
        byte[] content = "same-source".getBytes();
        String hash = Hashing.sha256(content);
        RoutingSnapshot current = CameraUpdateTestSupport.snapshot(hash, 1, 1);
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch allowDownload = new CountDownLatch(1);
        when(sourceClient.download()).thenAnswer(invocation -> {
            downloadStarted.countDown();
            assertThat(allowDownload.await(5, TimeUnit.SECONDS)).isTrue();
            return new DownloadedCameraSource(content, hash);
        });
        when(snapshotManager.current()).thenReturn(Optional.of(current));
        when(fileStore.currentSha256()).thenReturn(Optional.of(hash));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var firstUpdate = executor.submit(service::updateNow);
            assertThat(downloadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(service::updateNow)
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.code()).isEqualTo(
                                    ErrorCode.CAMERA_UPDATE_ALREADY_RUNNING));

            allowDownload.countDown();
            assertThat(firstUpdate.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(CameraUpdateStatus.NO_CHANGE);
        }
    }
}
