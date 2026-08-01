package cn.camera.safe.camera.update;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.CameraUpdateResult;
import cn.camera.safe.api.model.CameraUpdateStatus;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotBuilder;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public final class CameraUpdateService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CameraUpdateService.class);

    private final AppProperties properties;
    private final GraphHopperManager graphManager;
    private final RoutingSnapshotBuilder snapshotBuilder;
    private final RoutingSnapshotManager snapshotManager;
    private final CameraSourceClient sourceClient;
    private final CameraUpdateFileStore fileStore;
    private final AtomicBoolean running = new AtomicBoolean();

    public CameraUpdateService(
            AppProperties properties,
            GraphHopperManager graphManager,
            RoutingSnapshotBuilder snapshotBuilder,
            RoutingSnapshotManager snapshotManager,
            CameraSourceClient sourceClient,
            CameraUpdateFileStore fileStore) {
        this.properties = properties;
        this.graphManager = graphManager;
        this.snapshotBuilder = snapshotBuilder;
        this.snapshotManager = snapshotManager;
        this.sourceClient = sourceClient;
        this.fileStore = fileStore;
    }

    public CameraUpdateResult updateNow() {
        String updateId = UUID.randomUUID().toString();
        long updateStarted = System.nanoTime();
        LOGGER.info("Camera data update requested updateId={}", updateId);
        if (!graphManager.isReady()) {
            LOGGER.warn("Camera data update rejected updateId={} reason=routing_graph_not_ready",
                    updateId);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路网尚未就绪");
        }
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("Camera data update rejected updateId={} reason=update_already_running",
                    updateId);
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.CAMERA_UPDATE_ALREADY_RUNNING, "摄像头数据更新正在进行");
        }
        LOGGER.info("Camera data update started updateId={}", updateId);
        try {
            return performUpdate(updateId, updateStarted);
        } catch (RoutingSnapshotManager.RefreshAlreadyRunningException exception) {
            LOGGER.warn("Camera data update rejected updateId={} reason=snapshot_refresh_already_running "
                            + "elapsedMs={}",
                    updateId, elapsedMillis(updateStarted));
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.CAMERA_UPDATE_ALREADY_RUNNING, "摄像头快照刷新正在进行");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw updateFailed("摄像头数据更新被中断", exception, updateId, updateStarted);
        } catch (IOException | RuntimeException exception) {
            throw updateFailed("摄像头数据更新失败", exception, updateId, updateStarted);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private CameraUpdateResult performUpdate(String updateId, long updateStarted)
            throws IOException, InterruptedException {
        long phaseStarted = System.nanoTime();
        LOGGER.info("Camera data update phase started updateId={} phase=download maxBytes={}",
                updateId, properties.cameras().update().maxDownloadBytes());
        DownloadedCameraSource downloaded = sourceClient.download();
        LOGGER.info("Camera data update phase completed updateId={} phase=download bytes={} "
                        + "sourceSha256={} elapsedMs={}",
                updateId, downloaded.content().length, downloaded.sha256(),
                elapsedMillis(phaseStarted));

        phaseStarted = System.nanoTime();
        LOGGER.info("Camera data update phase started updateId={} phase=change_detection", updateId);
        RoutingSnapshot previous = snapshotManager.current().orElse(null);
        String currentFileHash = fileStore.currentSha256().orElse(null);
        if (previous != null
                && downloaded.sha256().equals(currentFileHash)
                && downloaded.sha256().equals(previous.cameraSnapshot().sourceSha256())) {
            LOGGER.info("Camera data update completed updateId={} status=NO_CHANGE sourceSha256={} "
                            + "changeDetectionElapsedMs={} totalElapsedMs={}",
                    updateId, downloaded.sha256(), elapsedMillis(phaseStarted),
                    elapsedMillis(updateStarted));
            return result(CameraUpdateStatus.NO_CHANGE, previous, previous.cameraSnapshot().sourceSha256());
        }
        LOGGER.info("Camera data update phase completed updateId={} phase=change_detection "
                        + "changed=true currentFileSha256={} currentSnapshotSha256={} elapsedMs={}",
                updateId, currentFileHash,
                previous == null ? null : previous.cameraSnapshot().sourceSha256(),
                elapsedMillis(phaseStarted));

        Path staged = null;
        Path prepared = null;
        boolean published = false;
        try {
            phaseStarted = System.nanoTime();
            LOGGER.info("Camera data update phase started updateId={} phase=stage_source", updateId);
            staged = fileStore.stage(downloaded);
            LOGGER.info("Camera data update phase completed updateId={} phase=stage_source path={} "
                            + "elapsedMs={}",
                    updateId, staged, elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("Camera data update phase started updateId={} phase=build_snapshot", updateId);
            RoutingSnapshot candidate = snapshotBuilder.build(staged);
            LOGGER.info("Camera data update phase completed updateId={} phase=build_snapshot "
                            + "sourceRecords={} retainedRecords={} matchedCameras={} "
                            + "unmatchedCameras={} blockedEdges={} elapsedMs={}",
                    updateId,
                    candidate.cameraSnapshot().sourceRecordCount(),
                    candidate.cameraSnapshot().retainedRecordCount(),
                    candidate.matchedCameraCount(),
                    candidate.unmatchedCameraIds().size(),
                    candidate.blockedEdges().blockedEdgeCount(),
                    elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("Camera data update phase started updateId={} phase=validate_candidate", updateId);
            validateCandidate(candidate, previous);
            LOGGER.info("Camera data update phase completed updateId={} phase=validate_candidate "
                            + "elapsedMs={}",
                    updateId, elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("Camera data update phase started updateId={} phase=publish", updateId);
            prepared = fileStore.prepareForPublication(staged);
            Path publicationFile = prepared;
            snapshotManager.publishCandidate(candidate, () -> fileStore.activate(publicationFile));
            published = true;
            LOGGER.info("Camera data update phase completed updateId={} phase=publish sourcePath={} "
                            + "elapsedMs={}",
                    updateId, fileStore.currentSource(), elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("Camera data update phase started updateId={} phase=retention_cleanup", updateId);
            try {
                fileStore.cleanupAfterSuccess(staged);
                LOGGER.info("Camera data update phase completed updateId={} phase=retention_cleanup "
                                + "elapsedMs={}",
                        updateId, elapsedMillis(phaseStarted));
            } catch (IOException exception) {
                LOGGER.warn("Camera data update phase failed updateId={} phase=retention_cleanup "
                                + "message={} elapsedMs={}",
                        updateId, exception.getMessage(), elapsedMillis(phaseStarted), exception);
            }
            String previousHash = previous == null ? null : previous.cameraSnapshot().sourceSha256();
            LOGGER.info("Camera data update completed updateId={} status=UPDATED sourceSha256={} "
                            + "previousSourceSha256={} totalElapsedMs={}",
                    updateId, downloaded.sha256(), previousHash, elapsedMillis(updateStarted));
            return result(CameraUpdateStatus.UPDATED, candidate, previousHash);
        } catch (IOException | RuntimeException exception) {
            if (!published) {
                LOGGER.warn("Camera data update candidate cleanup started updateId={} stagedPath={} "
                                + "preparedPath={}",
                        updateId, staged, prepared);
                cleanupFailedCandidate(staged, prepared);
            }
            throw exception;
        }
    }

    private void validateCandidate(RoutingSnapshot candidate, RoutingSnapshot previous) {
        AppProperties.Update update = properties.cameras().update();
        int sourceCount = candidate.cameraSnapshot().sourceRecordCount();
        if (sourceCount < update.minSourceRecordCount()) {
            throw new CameraUpdateRejectedException(
                    "camera source record count is below the configured minimum: " + sourceCount);
        }
        double matchRate = (double) candidate.matchedCameraCount()
                / candidate.cameraSnapshot().retainedRecordCount();
        if (matchRate < update.minMatchRate()) {
            throw new CameraUpdateRejectedException(
                    "camera match rate is below the configured minimum: " + matchRate);
        }
        if (previous == null) {
            return;
        }
        int previousSourceCount = previous.cameraSnapshot().sourceRecordCount();
        double sourceChange = ratioDifference(sourceCount, previousSourceCount);
        if (sourceChange > update.maxSourceCountChangeRatio()) {
            throw new CameraUpdateRejectedException(
                    "camera source record count change exceeds the configured maximum: " + sourceChange);
        }
        int blockedEdges = candidate.blockedEdges().blockedEdgeCount();
        int previousBlockedEdges = previous.blockedEdges().blockedEdgeCount();
        double blockedChange = ratioDifference(blockedEdges, previousBlockedEdges);
        if (blockedChange > update.maxBlockedEdgeChangeRatio()) {
            throw new CameraUpdateRejectedException(
                    "blocked edge count change exceeds the configured maximum: " + blockedChange);
        }
    }

    private void cleanupFailedCandidate(Path staged, Path prepared) {
        try {
            fileStore.discard(prepared);
        } catch (IOException exception) {
            LOGGER.warn("Could not delete prepared camera update file message={}", exception.getMessage());
        }
        try {
            fileStore.quarantine(staged);
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine failed camera update file message={}", exception.getMessage());
        }
    }

    private static CameraUpdateResult result(
            CameraUpdateStatus status,
            RoutingSnapshot snapshot,
            String previousSourceHash) {
        return new CameraUpdateResult(
                status,
                snapshot.cameraSnapshot().sourceSha256(),
                previousSourceHash,
                snapshot.cameraSnapshot().sourceRecordCount(),
                snapshot.cameraSnapshot().retainedRecordCount(),
                snapshot.cameraSnapshot().outsideSixRingRecordCount(),
                snapshot.cameraSnapshot().unrecognizedSixRingOutRecordCount(),
                snapshot.matchedCameraCount(),
                snapshot.unmatchedCameraIds().size(),
                snapshot.blockedEdges().blockedEdgeCount(),
                Instant.now());
    }

    private static double ratioDifference(int current, int previous) {
        if (previous == 0) {
            return current == 0 ? 0 : 1;
        }
        return Math.abs((double) current - previous) / previous;
    }

    private static BusinessException updateFailed(
            String message,
            Exception cause,
            String updateId,
            long updateStarted) {
        LOGGER.error("{} updateId={} type={} message={} totalElapsedMs={}",
                message, updateId, cause.getClass().getName(), cause.getMessage(),
                elapsedMillis(updateStarted), cause);
        return new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.CAMERA_UPDATE_FAILED,
                message,
                Map.of("reason", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
