package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static SixthRingBoundaryLoader loader() {
        return new SixthRingBoundaryLoader(new ObjectMapper());
    }

    private static Wgs84Coordinate point(double lng, double lat) {
        return new Wgs84Coordinate(lng, lat);
    }
}
