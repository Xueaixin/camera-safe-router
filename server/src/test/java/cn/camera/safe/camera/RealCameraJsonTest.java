package cn.camera.safe.camera;

import cn.camera.safe.coordinate.CoordinateConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealCameraJsonTest {
    @Test
    void validatesCurrentCameraFileWithoutModifyingIt() throws Exception {
        String configuredJson = System.getProperty("real.camera.json");
        assumeTrue(configuredJson != null && !configuredJson.isBlank(),
                "Set -Dreal.camera.json=<path-to-map.json> to run the real camera test");

        CameraLoadResult result = new CameraJsonLoader(new ObjectMapper(), new CoordinateConverter())
                .load(Path.of(configuredJson));

        assertThat(result.isValid()).isTrue();
        assertThat(result.sourceRecordCount()).isEqualTo(6_797);
        assertThat(result.sourceSha256())
                .isEqualTo("f2e1ce2392a2e1f029f42e0fa7728044c72475725dd40944794afb42c44ca65a");
        JsonNode candidates = new ObjectMapper().readTree(
                RealCameraJsonTest.class.getResourceAsStream("/fixtures/real-camera-candidates-50.json"));
        Set<String> candidateIds = new HashSet<>();
        candidates.forEach(item -> candidateIds.add(item.path("id").asText()));
        assertThat(candidateIds).hasSize(50);
        assertThat(result.cameras()).extracting(CameraPoint::id).containsAll(candidateIds);
        System.out.printf("REAL_CAMERA_JSON records=%d sha256=%s%n",
                result.sourceRecordCount(), result.sourceSha256());
    }
}
