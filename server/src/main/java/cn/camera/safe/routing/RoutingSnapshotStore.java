package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
public final class RoutingSnapshotStore {
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public RoutingSnapshotStore(ObjectMapper objectMapper, AppProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Path persist(RoutingSnapshot snapshot) throws IOException {
        Path directory = Path.of(properties.cameras().snapshotPath()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        String contentFingerprint = Hashing.sha256(
                "camera=" + snapshot.cameraSnapshot().sourceSha256()
                        + "|graph=" + snapshot.graphFingerprint()
                        + "|radius=" + snapshot.safetyRadiusMeters()
                        + "|retained=" + snapshot.cameraSnapshot().retainedRecordCount()
                        + "|matched=" + snapshot.matchedCameraCount()
                        + "|blocked=" + snapshot.blockedEdges().blockedEdgeVersion());
        Path target = directory.resolve("routing-snapshot-" + contentFingerprint + ".json");
        if (Files.isRegularFile(target)) {
            return target;
        }
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        PersistedSnapshot persisted = new PersistedSnapshot(
                snapshot.cameraSnapshot().version(),
                snapshot.cameraSnapshot().sourceSha256(),
                snapshot.cameraSnapshot().loadedAt(),
                snapshot.cameraSnapshot().sourceRecordCount(),
                snapshot.cameraSnapshot().retainedRecordCount(),
                snapshot.cameraSnapshot().outsideSixRingRecordCount(),
                snapshot.cameraSnapshot().unrecognizedSixRingOutRecordCount(),
                snapshot.cameraSnapshot().cameras().size(),
                snapshot.blockedEdges().blockedEdgeVersion(),
                snapshot.graphFingerprint(),
                snapshot.safetyRadiusMeters(),
                snapshot.blockedEdges().blockedEdgeCount(),
                snapshot.matchedCameraCount(),
                snapshot.unmatchedCameraIds(),
                snapshot.blockedEdges().blockedForward().stream().boxed().toList(),
                snapshot.blockedEdges().blockedReverse().stream().boxed().toList());
        try {
            objectMapper.writeValue(temporary.toFile(), persisted);
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("snapshot filesystem does not support atomic publication", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void prune() throws IOException {
        Path directory = Path.of(properties.cameras().snapshotPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            return;
        }
        int retentionCount = properties.cameras().update().snapshotRetentionCount();
        List<Path> snapshots;
        try (Stream<Path> entries = Files.list(directory)) {
            snapshots = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("routing-snapshot-"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(RoutingSnapshotStore::lastModified).reversed())
                    .toList();
        }
        for (int index = retentionCount; index < snapshots.size(); index++) {
            Files.deleteIfExists(snapshots.get(index));
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private record PersistedSnapshot(
            String cameraSnapshotVersion,
            String cameraSourceSha256,
            Instant loadedAt,
            int cameraSourceRecordCount,
            int cameraRetainedRecordCount,
            int cameraOutsideSixRingRecordCount,
            int cameraUnrecognizedSixRingOutRecordCount,
            int cameraCount,
            String blockedEdgeVersion,
            String graphFingerprint,
            double safetyRadiusMeters,
            int blockedEdgeCount,
            int matchedCameraCount,
            List<String> unmatchedCameraIds,
            List<Integer> blockedForwardEdgeIds,
            List<Integer> blockedReverseEdgeIds) {
    }
}
