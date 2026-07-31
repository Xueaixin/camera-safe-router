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
import java.util.List;

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
        String sourcePrefix = snapshot.cameraSnapshot().sourceSha256().substring(0, 12);
        Path target = directory.resolve("routing-snapshot-"
                + snapshot.cameraSnapshot().loadedAt().toEpochMilli() + "-" + sourcePrefix + ".json");
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
