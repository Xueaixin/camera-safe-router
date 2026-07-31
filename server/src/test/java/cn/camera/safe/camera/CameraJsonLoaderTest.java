package cn.camera.safe.camera;

import cn.camera.safe.coordinate.CoordinateConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CameraJsonLoaderTest {
    private final CameraJsonLoader loader = new CameraJsonLoader(
            new ObjectMapper(), new CoordinateConverter());

    @Test
    void loadsBareArrayAndRetainsDifferentIdsAtTheSameCoordinate() throws Exception {
        CameraLoadResult result = loader.load(fixture("cameras-valid-array.json"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.sourceRecordCount()).isEqualTo(2);
        assertThat(result.retainedRecordCount()).isEqualTo(2);
        assertThat(result.excludedRecordCount()).isZero();
        assertThat(result.cameras()).extracting(CameraPoint::id)
                .containsExactly("camera-a", "camera-b");
        assertThat(result.cameras()).extracting(camera -> camera.gcj02().lng()).containsOnly(116.329009);
        assertThat(result.cameras().getFirst().directionText()).isEqualTo("北向南");
        assertThat(result.sourceSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void acceptsSupportedObjectWrapperAndNumericStrings() throws Exception {
        CameraLoadResult result = loader.load(fixture("cameras-wrapped.json"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.retainedRecordCount()).isEqualTo(1);
        assertThat(result.cameras()).singleElement()
                .extracting(CameraPoint::id).isEqualTo("camera-wrapped");
    }

    @Test
    void excludesOnlySemanticOneAndRetainsMissingOrUnrecognizedValues() throws Exception {
        CameraLoadResult result = loader.load(fixture("cameras-six-ring-filter.json"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.sourceRecordCount()).isEqualTo(7);
        assertThat(result.retainedRecordCount()).isEqualTo(5);
        assertThat(result.outsideSixRingRecordCount()).isEqualTo(2);
        assertThat(result.unrecognizedSixRingOutRecordCount()).isEqualTo(3);
        assertThat(result.excludedRecordCount()).isEqualTo(2);
        assertThat(result.cameras()).extracting(CameraPoint::id)
                .containsExactly("eligible", "missing", "numeric-zero", "whitespace-zero", "unknown");
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void reportsDuplicateIdsBadCoordinatesAndMissingFieldsWithoutPublishingPartialSnapshot() throws Exception {
        CameraLoadResult result = loader.load(fixture("cameras-invalid.json"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.sourceRecordCount()).isEqualTo(4);
        assertThat(result.retainedRecordCount()).isEqualTo(4);
        assertThat(result.excludedRecordCount()).isZero();
        assertThat(result.cameras()).hasSize(1);
        assertThat(result.issues()).hasSize(3);
        assertThat(result.issues()).extracting(CameraValidationIssue::reason)
                .contains("duplicate Id", "longitude must be finite and between -180 and 180", "CameraType is required");
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(CameraJsonLoaderTest.class.getResource("/fixtures/" + name).toURI());
    }
}
