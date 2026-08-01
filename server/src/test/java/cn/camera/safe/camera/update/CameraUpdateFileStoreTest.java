package cn.camera.safe.camera.update;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.routing.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CameraUpdateFileStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyPublishesBacksUpAndPrunesOldSources() throws Exception {
        AppProperties properties = CameraUpdateTestSupport.properties(
                temporaryDirectory, "https://example.test/cameras");
        CameraUpdateFileStore store = new CameraUpdateFileStore(properties);
        Path current = store.currentSource();
        Files.createDirectories(current.getParent());
        Files.writeString(current, "old-0", StandardCharsets.UTF_8);

        for (int version = 1; version <= 3; version++) {
            byte[] content = ("new-" + version).getBytes(StandardCharsets.UTF_8);
            Path staged = store.stage(new DownloadedCameraSource(content, Hashing.sha256(content)));
            Path prepared = store.prepareForPublication(staged);
            store.activate(prepared);
            store.cleanupAfterSuccess(staged);
        }

        assertThat(Files.readString(current, StandardCharsets.UTF_8)).isEqualTo("new-3");
        try (var backups = Files.list(temporaryDirectory.resolve("backups"))) {
            assertThat(backups.filter(Files::isRegularFile).count()).isEqualTo(2);
        }
        try (var downloads = Files.list(temporaryDirectory.resolve("downloads"))) {
            assertThat(downloads.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void quarantinesFailedCandidatesAndAppliesRetention() throws Exception {
        AppProperties properties = CameraUpdateTestSupport.properties(
                temporaryDirectory, "https://example.test/cameras");
        CameraUpdateFileStore store = new CameraUpdateFileStore(properties);

        for (int version = 0; version < 3; version++) {
            byte[] content = ("invalid-" + version).getBytes(StandardCharsets.UTF_8);
            Path staged = store.stage(new DownloadedCameraSource(content, Hashing.sha256(content)));
            store.quarantine(staged);
        }

        try (var failed = Files.list(temporaryDirectory.resolve("failed"))) {
            assertThat(failed.filter(Files::isRegularFile).count()).isEqualTo(2);
        }
    }
}
