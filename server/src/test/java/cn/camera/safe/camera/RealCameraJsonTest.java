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
                "Set -Dreal.camera.json=<path-to-camera.json> to run the real camera test");

        CameraLoadResult result = new CameraJsonLoader(new ObjectMapper(), new CoordinateConverter())
                .load(Path.of(configuredJson));

        assertThat(result.isValid()).isTrue();
        assertThat(result.sourceRecordCount()).isEqualTo(6_803);
        assertThat(result.retainedRecordCount()).isEqualTo(5_707);
        assertThat(result.outsideSixRingRecordCount()).isEqualTo(1_096);
        assertThat(result.unrecognizedSixRingOutRecordCount()).isEqualTo(7);
        assertThat(result.cameras()).hasSize(5_707);
        assertThat(result.sourceSha256())
                .isEqualTo("0c16ed44ef2e24cd4759afba28d6448925db699a4d3b38b05980f076e6ac7404");
        JsonNode candidates = new ObjectMapper().readTree(
                RealCameraJsonTest.class.getResourceAsStream("/fixtures/real-camera-candidates-50.json"));
        Set<String> candidateIds = new HashSet<>();
        candidates.forEach(item -> candidateIds.add(item.path("id").asText()));
        assertThat(candidateIds).hasSize(50);
        Set<String> loadedCandidateIds = new HashSet<>();
        result.cameras().stream()
                .map(CameraPoint::id)
                .filter(candidateIds::contains)
                .forEach(loadedCandidateIds::add);
        assertThat(loadedCandidateIds).hasSize(45);
        System.out.printf(
                "REAL_CAMERA_JSON source=%d retained=%d outside=%d unrecognizedIsSixRingOut=%d sha256=%s%n",
                result.sourceRecordCount(),
                result.retainedRecordCount(),
                result.outsideSixRingRecordCount(),
                result.unrecognizedSixRingOutRecordCount(),
                result.sourceSha256());
    }
}
