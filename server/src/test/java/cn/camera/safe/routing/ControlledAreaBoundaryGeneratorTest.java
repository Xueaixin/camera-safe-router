package cn.camera.safe.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ControlledAreaBoundaryGeneratorTest {
    private static final String PBF_HASH = "b".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsProvincialBorderFromTongzhouAdminLevel4Ways() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode tongzhou = tongzhouRoot();
        addBorderFeature(objectMapper, tongzhou, new double[][] {{2, 8}, {5, 8}});
        addBorderFeature(objectMapper, tongzhou, new double[][] {{8, 2}, {8, 5}});

        Geometry border = ControlledAreaBoundaryGenerator.provincialBorder(tongzhou);

        assertThat(border.getGeometryType()).isEqualTo("MultiLineString");
        assertThat(border.getNumGeometries()).isEqualTo(2);
    }

    @Test
    void writesSchemaV4BoundaryWithProvincialBorderFeature() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path sixthRing = writeSixthRingLines();
        Path tongzhouPath = writeTongzhou();
        Path output = temporaryDirectory.resolve("candidate-v4.geojson");

        ControlledAreaBoundaryGenerator.generateAndWrite(
                objectMapper,
                objectMapper.readTree(sixthRing.toFile()),
                objectMapper.readTree(tongzhouPath.toFile()),
                output,
                sixthRing,
                tongzhouPath,
                PBF_HASH,
                false);

        JsonNode root = objectMapper.readTree(output.toFile());
        assertThat(root.path("schemaVersion").asInt()).isEqualTo(4);
        JsonNode provincial = null;
        for (JsonNode feature : root.path("features")) {
            if ("provincial_border".equals(
                    feature.path("properties").path("role").asText())) {
                provincial = feature;
            }
        }
        assertThat(provincial).isNotNull();
        assertThat(provincial.path("geometry").path("type").asText())
                .isEqualTo("MultiLineString");

        SixthRingBoundaryLoader.LoadedBoundary loaded =
                new SixthRingBoundaryLoader(objectMapper).load(output, PBF_HASH, false);
        assertThat(loaded.boundary().provincialBorder()).isPresent();
    }

    private Path writeSixthRingLines() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.withArray("features").addObject()
                .put("type", "Feature")
                .set("geometry", lineNode(objectMapper,
                        new double[][] {{0, 0}, {10, 0}, {10, 10}, {0, 10}, {0, 0}}));
        root.withArray("features").addObject()
                .put("type", "Feature")
                .set("geometry", lineNode(objectMapper,
                        new double[][] {{2, 2}, {8, 2}, {8, 8}, {2, 8}, {2, 2}}));
        Path path = temporaryDirectory.resolve("sixth-ring-lines.geojson");
        Files.writeString(path, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
        return path;
    }

    private Path writeTongzhou() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        ObjectNode feature = root.withArray("features").addObject();
        feature.put("type", "Feature");
        feature.set("properties", objectMapper.createObjectNode()
                .put("wikidata", "Q393836")
                .put("admin_level", "6"));
        ObjectNode polygon = objectMapper.createObjectNode();
        polygon.put("type", "Polygon");
        ArrayNode rings = polygon.putArray("coordinates");
        ArrayNode ring = rings.addArray();
        for (double[] coordinate : new double[][] {
                {2, 2}, {8, 2}, {8, 8}, {2, 8}, {2, 2}}) {
            ArrayNode value = ring.addArray();
            value.add(coordinate[0]);
            value.add(coordinate[1]);
        }
        feature.set("geometry", polygon);

        ObjectNode borderFeature = root.withArray("features").addObject();
        borderFeature.put("type", "Feature");
        borderFeature.set("properties", objectMapper.createObjectNode()
                .put("boundary", "administrative")
                .put("admin_level", "4"));
        borderFeature.set("geometry", lineNode(objectMapper,
                new double[][] {{2, 8}, {5, 8}}));
        ObjectNode secondBorderFeature = root.withArray("features").addObject();
        secondBorderFeature.put("type", "Feature");
        secondBorderFeature.set("properties", objectMapper.createObjectNode()
                .put("boundary", "administrative")
                .put("admin_level", "4"));
        secondBorderFeature.set("geometry", lineNode(objectMapper,
                new double[][] {{8, 2}, {8, 5}}));

        Path path = temporaryDirectory.resolve("tongzhou-boundary.geojson");
        Files.writeString(path, objectMapper.writeValueAsString(root), StandardCharsets.UTF_8);
        return path;
    }

    private ObjectNode tongzhouRoot() {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("type", "FeatureCollection");
        ObjectNode feature = root.withArray("features").addObject();
        feature.put("type", "Feature");
        feature.set("properties", new ObjectMapper().createObjectNode()
                .put("wikidata", "Q393836")
                .put("admin_level", "6"));
        ObjectNode polygon = new ObjectMapper().createObjectNode();
        polygon.put("type", "Polygon");
        ArrayNode rings = polygon.putArray("coordinates");
        ArrayNode ring = rings.addArray();
        for (double[] coordinate : new double[][] {
                {2, 2}, {8, 2}, {8, 8}, {2, 8}, {2, 2}}) {
            ArrayNode value = ring.addArray();
            value.add(coordinate[0]);
            value.add(coordinate[1]);
        }
        feature.set("geometry", polygon);
        return root;
    }

    private static ObjectNode lineNode(ObjectMapper objectMapper, double[][] coordinates) {
        ObjectNode line = objectMapper.createObjectNode();
        line.put("type", "LineString");
        ArrayNode values = line.putArray("coordinates");
        for (double[] coordinate : coordinates) {
            ArrayNode value = values.addArray();
            value.add(coordinate[0]);
            value.add(coordinate[1]);
        }
        return line;
    }

    private static void addBorderFeature(
            ObjectMapper objectMapper,
            ObjectNode root,
            double[][] coordinates) {
        ObjectNode feature = root.withArray("features").addObject();
        feature.put("type", "Feature");
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("boundary", "administrative");
        properties.put("admin_level", "4");
        feature.set("properties", properties);
        feature.set("geometry", lineNode(objectMapper, coordinates));
    }
}
