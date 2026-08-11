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
        LOGGER.info("收到摄像头数据更新请求 更新ID={}", updateId);
        if (!graphManager.isReady()) {
            LOGGER.warn("摄像头数据更新被拒绝 更新ID={} 原因=路网未就绪",
                    updateId);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.ROUTING_NOT_READY, "路网尚未就绪");
        }
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("摄像头数据更新被拒绝 更新ID={} 原因=已有更新正在执行",
                    updateId);
            throw new BusinessException(HttpStatus.CONFLICT,
                    ErrorCode.CAMERA_UPDATE_ALREADY_RUNNING, "摄像头数据更新正在进行");
        }
        LOGGER.info("摄像头数据更新开始 更新ID={}", updateId);
        try {
            return performUpdate(updateId, updateStarted);
        } catch (RoutingSnapshotManager.RefreshAlreadyRunningException exception) {
            LOGGER.warn("摄像头数据更新被拒绝 更新ID={} 原因=快照刷新正在执行 "
                            + "总耗时毫秒={}",
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
        LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=下载 最大字节数={}",
                updateId, properties.cameras().update().maxDownloadBytes());
        DownloadedCameraSource downloaded = sourceClient.download();
        LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=下载 字节数={} "
                        + "源SHA256={} 耗时毫秒={}",
                updateId, downloaded.content().length, downloaded.sha256(),
                elapsedMillis(phaseStarted));

        phaseStarted = System.nanoTime();
        LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=变更检测", updateId);
        RoutingSnapshot previous = snapshotManager.current().orElse(null);
        String currentFileHash = fileStore.currentSha256().orElse(null);
        if (previous != null
                && downloaded.sha256().equals(currentFileHash)
                && downloaded.sha256().equals(previous.cameraSnapshot().sourceSha256())) {
            LOGGER.info("摄像头数据更新完成 更新ID={} 状态=无变化 源SHA256={} "
                            + "变更检测耗时毫秒={} 总耗时毫秒={}",
                    updateId, downloaded.sha256(), elapsedMillis(phaseStarted),
                    elapsedMillis(updateStarted));
            return result(CameraUpdateStatus.NO_CHANGE, previous, previous.cameraSnapshot().sourceSha256());
        }
        LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=变更检测 "
                        + "检测到变化=true 当前文件SHA256={} 当前快照SHA256={} 耗时毫秒={}",
                updateId, currentFileHash,
                previous == null ? null : previous.cameraSnapshot().sourceSha256(),
                elapsedMillis(phaseStarted));

        Path staged = null;
        Path prepared = null;
        boolean published = false;
        try {
            phaseStarted = System.nanoTime();
            LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=暂存源文件", updateId);
            staged = fileStore.stage(downloaded);
            LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=暂存源文件 路径={} "
                            + "耗时毫秒={}",
                    updateId, staged, elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=构建路由快照", updateId);
            RoutingSnapshot candidate = snapshotBuilder.build(staged);
            LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=构建路由快照 "
                            + "源记录数={} 保留记录数={} 受控摄像头数={} 界外解禁数={} "
                            + "匹配摄像头数={} 仅命中高速豁免边数={} 未匹配摄像头数={} "
                            + "禁行边数={} 耗时毫秒={}",
                    updateId,
                    candidate.cameraSnapshot().sourceRecordCount(),
                    candidate.cameraSnapshot().retainedRecordCount(),
                    candidate.restrictedCameraCount(),
                    candidate.outsideControlAreaCameraCount(),
                    candidate.matchedCameraCount(),
                    candidate.highwayExemptCameraCount(),
                    candidate.unmatchedCameraIds().size(),
                    candidate.blockedEdges().blockedEdgeCount(),
                    elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=校验候选", updateId);
            validateCandidate(candidate, previous);
            LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=校验候选 "
                            + "耗时毫秒={}",
                    updateId, elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=发布", updateId);
            prepared = fileStore.prepareForPublication(staged);
            Path publicationFile = prepared;
            snapshotManager.publishCandidate(candidate, () -> fileStore.activate(publicationFile));
            published = true;
            LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=发布 源文件路径={} "
                            + "耗时毫秒={}",
                    updateId, fileStore.currentSource(), elapsedMillis(phaseStarted));

            phaseStarted = System.nanoTime();
            LOGGER.info("摄像头数据更新阶段开始 更新ID={} 阶段=清理历史文件", updateId);
            try {
                fileStore.cleanupAfterSuccess(staged);
                LOGGER.info("摄像头数据更新阶段完成 更新ID={} 阶段=清理历史文件 "
                                + "耗时毫秒={}",
                        updateId, elapsedMillis(phaseStarted));
            } catch (IOException exception) {
                LOGGER.warn("摄像头数据更新阶段失败 更新ID={} 阶段=清理历史文件 "
                                + "错误信息={} 耗时毫秒={}",
                        updateId, exception.getMessage(), elapsedMillis(phaseStarted), exception);
            }
            String previousHash = previous == null ? null : previous.cameraSnapshot().sourceSha256();
            LOGGER.info("摄像头数据更新完成 更新ID={} 状态=已更新 源SHA256={} "
                            + "上一版本源SHA256={} 总耗时毫秒={}",
                    updateId, downloaded.sha256(), previousHash, elapsedMillis(updateStarted));
            return result(CameraUpdateStatus.UPDATED, candidate, previousHash);
        } catch (IOException | RuntimeException exception) {
            if (!published) {
                LOGGER.warn("摄像头数据更新候选清理开始 更新ID={} 暂存路径={} "
                                + "发布准备路径={}",
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
                    "摄像头源记录数低于配置的最小值: " + sourceCount);
        }
        double matchRate = candidate.restrictedCameraCount() == 0
                ? 1.0
                : (double) candidate.matchedCameraCount() / candidate.restrictedCameraCount();
        if (matchRate < update.minMatchRate()) {
            throw new CameraUpdateRejectedException(
                    "摄像头匹配率低于配置的最小值: " + matchRate);
        }
        if (previous == null) {
            return;
        }
        int previousSourceCount = previous.cameraSnapshot().sourceRecordCount();
        double sourceChange = ratioDifference(sourceCount, previousSourceCount);
        if (sourceChange > update.maxSourceCountChangeRatio()) {
            throw new CameraUpdateRejectedException(
                    "摄像头源记录数变化率超过配置的最大值: " + sourceChange);
        }
        int blockedEdges = candidate.blockedEdges().blockedEdgeCount();
        int previousBlockedEdges = previous.blockedEdges().blockedEdgeCount();
        double blockedChange = ratioDifference(blockedEdges, previousBlockedEdges);
        if (blockedChange > update.maxBlockedEdgeChangeRatio()) {
            throw new CameraUpdateRejectedException(
                    "禁行边数量变化率超过配置的最大值: " + blockedChange);
        }
    }

    private void cleanupFailedCandidate(Path staged, Path prepared) {
        try {
            fileStore.discard(prepared);
        } catch (IOException exception) {
            LOGGER.warn("无法删除摄像头更新发布准备文件 错误信息={}", exception.getMessage());
        }
        try {
            fileStore.quarantine(staged);
        } catch (IOException exception) {
            LOGGER.warn("无法隔离失败的摄像头更新文件 错误信息={}", exception.getMessage());
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
                snapshot.restrictedCameraCount(),
                snapshot.outsideControlAreaCameraCount(),
                snapshot.cameraOutsideMarginMeters(),
                snapshot.controlBoundaryVersion(),
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
        LOGGER.error("{} 更新ID={} 异常类型={} 错误信息={} 总耗时毫秒={}",
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
