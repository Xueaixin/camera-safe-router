package cn.camera.safe.camera.update;

import cn.camera.safe.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CameraSourceClientTest {
    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsAnEmptyBodyAndDownloadsJson() throws Exception {
        byte[] json = "[{\"Id\":\"1\"}]".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cameras", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestBody().readAllBytes()).isEmpty();
            assertThat(exchange.getRequestHeaders().getFirst("Content-Length")).isEqualTo("0");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json);
            exchange.close();
        });
        server.start();

        CameraSourceClient client = new CameraSourceClient(properties(sourceUrl(), 1024));
        DownloadedCameraSource downloaded = client.download();

        assertThat(downloaded.content()).isEqualTo(json);
        assertThat(downloaded.sha256()).hasSize(64);
    }

    @Test
    void rejectsUnexpectedContentTypeAndOversizedBodies() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cameras", exchange -> {
            byte[] body = new byte[128];
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        CameraSourceClient client = new CameraSourceClient(properties(sourceUrl(), 64));

        assertThatThrownBy(client::download)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void canBeCreatedByTheSpringContainer() {
        AppProperties properties = CameraUpdateTestSupport.properties(
                temporaryDirectory, "https://example.test/cameras");

        new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withBean(CameraSourceClient.class)
                .run(context -> assertThat(context).hasSingleBean(CameraSourceClient.class));
    }

    private String sourceUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/cameras";
    }

    private AppProperties properties(String sourceUrl, int maxBytes) {
        AppProperties originalProperties = CameraUpdateTestSupport
                .properties(temporaryDirectory, sourceUrl);
        AppProperties.Update original = originalProperties.cameras().update();
        AppProperties.Update update = new AppProperties.Update(
                original.enabled(), original.sourceUrl(), original.cron(), original.zone(),
                original.downloadPath(), original.failedPath(), original.backupPath(),
                original.connectTimeout(), original.requestTimeout(), maxBytes,
                original.minSourceRecordCount(), original.maxSourceCountChangeRatio(),
                original.minMatchRate(), original.maxBlockedEdgeChangeRatio(),
                original.backupRetentionCount(), original.snapshotRetentionCount(),
                original.failedRetentionCount());
        return new AppProperties(
                originalProperties.routing(),
                new AppProperties.Cameras(
                        originalProperties.cameras().jsonPath(),
                        originalProperties.cameras().snapshotPath(),
                        originalProperties.cameras().sourceCoordinateVerified(),
                        originalProperties.cameras().maxBboxResults(),
                        originalProperties.cameras().maxBboxSpanDegrees(),
                        update),
                originalProperties.admin());
    }
}
