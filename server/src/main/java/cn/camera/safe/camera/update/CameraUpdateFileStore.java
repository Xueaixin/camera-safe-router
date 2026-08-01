package cn.camera.safe.camera.update;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.routing.Hashing;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public final class CameraUpdateFileStore {
    private final AppProperties properties;

    public CameraUpdateFileStore(AppProperties properties) {
        this.properties = properties;
    }

    public Path stage(DownloadedCameraSource source) throws IOException {
        Path directory = downloadDirectory();
        Files.createDirectories(directory);
        String name = "camera-" + Instant.now().toEpochMilli()
                + "-" + source.sha256().substring(0, 12) + ".json";
        Path target = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, name, ".part");
        try {
            Files.write(temporary, source.content());
            moveAtomically(temporary, target);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path prepareForPublication(Path stagedSource) throws IOException {
        Path current = currentSource();
        Path parent = current.getParent();
        if (parent == null) {
            throw new IOException("camera source path must have a parent directory");
        }
        Files.createDirectories(parent);
        Path prepared = Files.createTempFile(parent, ".camera-update-", ".tmp");
        try {
            Files.copy(stagedSource, prepared, StandardCopyOption.REPLACE_EXISTING);
            return prepared;
        } catch (IOException exception) {
            Files.deleteIfExists(prepared);
            throw exception;
        }
    }

    public void activate(Path preparedSource) throws IOException {
        Path current = currentSource();
        if (!preparedSource.toAbsolutePath().normalize().getParent().equals(current.getParent())) {
            throw new IOException("prepared camera source is not on the publication filesystem");
        }
        backupCurrent(current);
        moveAtomically(preparedSource, current);
    }

    public Optional<String> currentSha256() throws IOException {
        Path current = currentSource();
        return Files.isRegularFile(current) ? Optional.of(Hashing.sha256(current)) : Optional.empty();
    }

    public void discard(Path path) throws IOException {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }

    public void quarantine(Path stagedSource) throws IOException {
        if (stagedSource == null || !Files.isRegularFile(stagedSource)) {
            return;
        }
        Path failedDirectory = failedDirectory();
        Files.createDirectories(failedDirectory);
        Path target = failedDirectory.resolve(stagedSource.getFileName());
        Path temporary = Files.createTempFile(failedDirectory, target.getFileName().toString(), ".tmp");
        try {
            Files.copy(stagedSource, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporary, target);
            Files.deleteIfExists(stagedSource);
        } finally {
            Files.deleteIfExists(temporary);
        }
        prune(failedDirectory, "camera-", properties.cameras().update().failedRetentionCount());
    }

    public void cleanupAfterSuccess(Path stagedSource) throws IOException {
        discard(stagedSource);
        prune(backupDirectory(), "camera-", properties.cameras().update().backupRetentionCount());
        prune(failedDirectory(), "camera-", properties.cameras().update().failedRetentionCount());
    }

    public Path currentSource() {
        return Path.of(properties.cameras().jsonPath()).toAbsolutePath().normalize();
    }

    private void backupCurrent(Path current) throws IOException {
        if (!Files.isRegularFile(current)) {
            return;
        }
        Path directory = backupDirectory();
        Files.createDirectories(directory);
        String hash = Hashing.sha256(current);
        Path target = directory.resolve("camera-" + hash + ".json");
        if (Files.isRegularFile(target)) {
            Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
            return;
        }
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.copy(current, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path downloadDirectory() {
        return Path.of(properties.cameras().update().downloadPath()).toAbsolutePath().normalize();
    }

    private Path failedDirectory() {
        return Path.of(properties.cameras().update().failedPath()).toAbsolutePath().normalize();
    }

    private Path backupDirectory() {
        return Path.of(properties.cameras().update().backupPath()).toAbsolutePath().normalize();
    }

    private static void prune(Path directory, String prefix, int retentionCount) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(CameraUpdateFileStore::lastModified).reversed())
                    .toList();
        }
        for (int index = retentionCount; index < files.size(); index++) {
            Files.deleteIfExists(files.get(index));
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("camera data filesystem does not support atomic publication", exception);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }
}
