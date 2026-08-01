package cn.camera.safe.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePathConfigurationTest {
    @Test
    void defaultsAllRuntimeFilesUnderOneDriveRootDirectory() throws IOException {
        MockEnvironment environment = applicationEnvironment();

        assertThat(environment.getRequiredProperty("app.routing.pbf-path"))
                .isEqualTo("/camera-safe-routing-data/osm/beijing-latest.osm.pbf");
        assertThat(environment.getRequiredProperty("app.routing.graph-cache-path"))
                .isEqualTo("/camera-safe-routing-data/graph-cache/beijing");
        assertThat(environment.getRequiredProperty("app.cameras.json-path"))
                .isEqualTo("/camera-safe-routing-data/cameras/camera.json");
        assertThat(environment.getRequiredProperty("app.cameras.snapshot-path"))
                .isEqualTo("/camera-safe-routing-data/snapshots");
        assertThat(environment.getRequiredProperty("app.cameras.update.download-path"))
                .isEqualTo("/camera-safe-routing-data/downloads/cameras");
        assertThat(environment.getRequiredProperty("app.cameras.update.failed-path"))
                .isEqualTo("/camera-safe-routing-data/work/cameras/failed");
        assertThat(environment.getRequiredProperty("app.cameras.update.backup-path"))
                .isEqualTo("/camera-safe-routing-data/backups/cameras");
        assertThat(environment.getRequiredProperty("app.cameras.update.enabled"))
                .isEqualTo("false");
    }

    @Test
    void dataRootCanBeRelocatedWithoutOverridingEveryPath() throws IOException {
        MockEnvironment environment = applicationEnvironment()
                .withProperty("ROUTING_DATA_ROOT", "R:/routing-data");

        assertThat(environment.getRequiredProperty("app.routing.pbf-path"))
                .isEqualTo("R:/routing-data/osm/beijing-latest.osm.pbf");
        assertThat(environment.getRequiredProperty("app.routing.graph-cache-path"))
                .isEqualTo("R:/routing-data/graph-cache/beijing");
        assertThat(environment.getRequiredProperty("app.cameras.json-path"))
                .isEqualTo("R:/routing-data/cameras/camera.json");
        assertThat(environment.getRequiredProperty("app.cameras.snapshot-path"))
                .isEqualTo("R:/routing-data/snapshots");
        assertThat(environment.getRequiredProperty("app.cameras.update.download-path"))
                .isEqualTo("R:/routing-data/downloads/cameras");
        assertThat(environment.getRequiredProperty("app.cameras.update.failed-path"))
                .isEqualTo("R:/routing-data/work/cameras/failed");
        assertThat(environment.getRequiredProperty("app.cameras.update.backup-path"))
                .isEqualTo("R:/routing-data/backups/cameras");
    }

    private static MockEnvironment applicationEnvironment() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader().load(
                        "application",
                        new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
