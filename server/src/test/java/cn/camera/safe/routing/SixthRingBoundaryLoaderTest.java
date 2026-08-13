package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.camera.safe.routing.SixthRingBoundary.Location.BOUNDARY;
import static cn.camera.safe.routing.SixthRingBoundary.Location.INSIDE;
import static cn.camera.safe.routing.SixthRingBoundary.Location.OUTSIDE;
import static org.assertj.core.api.Assertions.assertThat;

class SixthRingBoundaryLoaderTest {
    private static final String PBF_HASH = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheAuthoritativeControlledAreaAsAMultiPolygon() throws Exception {
        Path input = write("""
                {
                  "type": "MultiPolygon",
                  "coordinates": [
                    [[[0,0],[1,0],[1,1],[0,1],[0,0]]],
                    [[[2,0],[3,0],[3,1],[2,1],[2,0]]]
                  ]
                }
                """, "controlled_area");

        SixthRingBoundaryLoader.LoadedBoundary loaded = loader().load(input, PBF_HASH, false);

        assertThat(loaded.boundary().controlledArea().getGeometryType())
                .isEqualTo("MultiPolygon");
        assertThat(loaded.boundary().locate(point(0.5, 0.5))).isEqualTo(INSIDE);
        assertThat(loaded.boundary().locate(point(2.5, 0.5))).isEqualTo(INSIDE);
        assertThat(loaded.boundary().locate(point(1.5, 0.5))).isEqualTo(OUTSIDE);
        assertThat(loaded.boundary().locate(point(1, 0.5))).isEqualTo(BOUNDARY);
    }

    @Test
    void temporarilyLoadsTheLegacyInsideBoundaryAsTheControlledArea() throws Exception {
        Path input = write("""
                {
                  "type": "Polygon",
                  "coordinates": [[[0,0],[1,0],[1,1],[0,1],[0,0]]]
                }
                """, "inside_boundary");

        SixthRingBoundaryLoader.LoadedBoundary loaded = loader().load(input, PBF_HASH, false);

        assertThat(loaded.boundary().locate(point(0.5, 0.5))).isEqualTo(INSIDE);
        assertThat(loaded.boundary().locate(point(1.5, 0.5))).isEqualTo(OUTSIDE);
    }

    @Test
    void loadsSchemaV4WithProvincialBorder() throws Exception {
        Path input = writeSchemaV4(true);

        SixthRingBoundaryLoader.LoadedBoundary loaded = loader().load(input, PBF_HASH, false);

        assertThat(loaded.boundary().provincialBorder()).hasValueSatisfying(border -> {
            assertThat(border.getGeometryType()).isEqualTo("LineString");
            assertThat(border.getNumPoints()).isGreaterThan(1);
        });
    }

    @Test
    void rejectsSchemaV4WithoutProvincialBorder() throws Exception {
        Path input = writeSchemaV4(false);

        assertThatThrownBy(() -> loader().load(input, PBF_HASH, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provincial_border");
    }

    private Path write(String geometry, String role) throws Exception {
        Path input = temporaryDirectory.resolve("controlled-area.geojson");
        Files.writeString(input, """
                {
                  "type": "FeatureCollection",
                  "coordinateSystem": "WGS84",
                  "sourcePbfSha256": "%s",
                  "approvedForProduction": false,
                  "features": [{
                    "type": "Feature",
                    "properties": {"role": "%s"},
                    "geometry": %s
                  }]
                }
                """.formatted(PBF_HASH, role, geometry), StandardCharsets.UTF_8);
        return input;
    }

    private Path writeSchemaV4(boolean withProvincialBorder) throws Exception {
        Path input = temporaryDirectory.resolve("controlled-area-v4.geojson");
        String borderFeature = withProvincialBorder
                ? """
                  ,{
                    "type": "Feature",
                    "properties": {"role": "provincial_border"},
                    "geometry": {
                      "type": "LineString",
                      "coordinates": [[0,0],[4,0]]
                    }
                  }
                  """
                : "";
        Files.writeString(input, """
                {
                  "type": "FeatureCollection",
                  "coordinateSystem": "WGS84",
                  "schemaVersion": 4,
                  "sourcePbfSha256": "%s",
                  "approvedForProduction": false,
                  "features": [
                    {
                      "type": "Feature",
                      "properties": {"role": "controlled_area"},
                      "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[0,0],[4,0],[4,4],[0,4],[0,0]]]
                      }
                    },
                    {
                      "type": "Feature",
                      "properties": {"role": "sixth_ring_area"},
                      "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[0,0],[4,0],[4,4],[0,4],[0,0]]]
                      }
                    },
                    {
                      "type": "Feature",
                      "properties": {"role": "tongzhou_area"},
                      "geometry": {
                        "type": "Polygon",
                        "coordinates": [[[0,0],[4,0],[4,4],[0,4],[0,0]]]
                      }
                    }%s
                  ]
                }
                """.formatted(PBF_HASH, borderFeature), StandardCharsets.UTF_8);
        return input;
    }

    private static SixthRingBoundaryLoader loader() {
        return new SixthRingBoundaryLoader(new ObjectMapper());
    }

    private static Wgs84Coordinate point(double lng, double lat) {
        return new Wgs84Coordinate(lng, lat);
    }
}
