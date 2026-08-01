package cn.camera.safe.routing;

import cn.camera.safe.camera.update.CameraUpdateTestSupport;
import cn.camera.safe.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingSnapshotStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deduplicatesByContentAndPrunesToTheConfiguredLimit() throws Exception {
        AppProperties properties = CameraUpdateTestSupport.properties(
                temporaryDirectory, "https://example.test/cameras");
        RoutingSnapshotStore store = new RoutingSnapshotStore(
                new ObjectMapper().findAndRegisterModules(), properties);
        RoutingSnapshot first = CameraUpdateTestSupport.snapshot("a".repeat(64), 1, 1);

        Path firstPath = store.persist(first);
        Path duplicatePath = store.persist(first);
        store.persist(CameraUpdateTestSupport.snapshot("b".repeat(64), 1, 1));
        store.persist(CameraUpdateTestSupport.snapshot("c".repeat(64), 1, 1));
        store.prune();

        assertThat(duplicatePath).isEqualTo(firstPath);
        try (var snapshots = Files.list(temporaryDirectory.resolve("snapshots"))) {
            assertThat(snapshots.filter(Files::isRegularFile).count()).isEqualTo(2);
        }
    }
}
