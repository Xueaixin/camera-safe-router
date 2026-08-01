package cn.camera.safe.camera.update;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.routing.Hashing;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

@Component
public final class CameraSourceClient {
    private final AppProperties.Update properties;
    private final HttpClient httpClient;

    public CameraSourceClient(AppProperties appProperties) {
        this.properties = appProperties.cameras().update();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public DownloadedCameraSource download() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.sourceUrl()))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .header("User-Agent", "camera-safe-router/0.1")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("camera source returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json")) {
                throw new IOException("camera source returned unexpected Content-Type: " + contentType);
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declaredLength > properties.maxDownloadBytes()) {
                throw new IOException("camera source response exceeds the configured size limit");
            }
            byte[] content = readLimited(body, properties.maxDownloadBytes());
            if (content.length == 0) {
                throw new IOException("camera source returned an empty response");
            }
            return new DownloadedCameraSource(content, Hashing.sha256(content));
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("camera source response exceeds the configured size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
