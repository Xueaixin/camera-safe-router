package cn.camera.safe.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ControlledAreaBoundaryPocTest {

    @Test
    void generatesTheSixthRingAndTongzhouUnionCandidate() throws Exception {
        String configuredSixthRing = System.getProperty("controlled.area.sixth-ring-boundary");
        String configuredTongzhou = System.getProperty("controlled.area.tongzhou-geojson");
        String configuredOutput = System.getProperty("controlled.area.output");
        String configuredReport = System.getProperty("controlled.area.report");
        assumeTrue(configuredSixthRing != null && !configuredSixthRing.isBlank()
                        && configuredTongzhou != null && !configuredTongzhou.isBlank()
                        && configuredOutput != null && !configuredOutput.isBlank()
                        && configuredReport != null && !configuredReport.isBlank(),
                "Set controlled-area input and output properties to run this POC");

        Path sixthRingPath = Path.of(configuredSixthRing).toAbsolutePath().normalize();
        Path tongzhouPath = Path.of(configuredTongzhou).toAbsolutePath().normalize();
        Path outputPath = Path.of(configuredOutput).toAbsolutePath().normalize();
        Path reportPath = Path.of(configuredReport).toAbsolutePath().normalize();
        ObjectMapper objectMapper = new ObjectMapper();

        JsonNode sixthRoot = objectMapper.readTree(sixthRingPath.toFile());
        String pbfHash = sixthRoot.path("sourcePbfSha256").asText();
        assertThat(pbfHash).matches("[0-9a-fA-F]{64}");
        Geometry sixthRingInside = featureGeometry(sixthRoot, "inside_boundary");

        JsonNode tongzhouRoot = objectMapper.readTree(tongzhouPath.toFile());
        JsonNode tongzhouFeature = findTongzhouFeature(tongzhouRoot);
        Geometry tongzhou = SixthRingBoundaryLoader.polygonalGeometry(
                tongzhouFeature.path("geometry"));

        Geometry fixedSixthRingInside = GeometryFixer.fix(sixthRingInside);
        Geometry fixedTongzhou = GeometryFixer.fix(tongzhou);
        Geometry controlledArea = fixedSixthRingInside.union(fixedTongzhou);
        controlledArea = GeometryFixer.fix(controlledArea);
        assertThat(controlledArea).isInstanceOfAny(Polygon.class, MultiPolygon.class);
        assertThat(controlledArea.isEmpty()).isFalse();
        assertThat(controlledArea.isValid()).isTrue();
        assertThat(fixedSixthRingInside.difference(controlledArea).getArea())
                .isLessThan(1e-12);
        assertThat(fixedTongzhou.difference(controlledArea).getArea())
                .isLessThan(1e-12);
        assertThat(fixedSixthRingInside.intersects(fixedTongzhou)).isTrue();

        writeCandidate(
                objectMapper, outputPath, sixthRingPath, tongzhouPath,
                pbfHash.toLowerCase(Locale.ROOT), controlledArea,
                fixedSixthRingInside, fixedTongzhou);
        writeReport(
                reportPath, outputPath, sixthRingPath, tongzhouPath,
                pbfHash.toLowerCase(Locale.ROOT),
                fixedSixthRingInside, fixedTongzhou, controlledArea);

        SixthRingBoundaryLoader.LoadedBoundary loaded = new SixthRingBoundaryLoader(objectMapper)
                .load(outputPath, pbfHash, false);
        assertThat(loaded.boundary().controlledArea().equalsTopo(controlledArea)).isTrue();
        assertThat(loaded.boundary().sixthRingArea()).hasValueSatisfying(
                area -> assertThat(area.equalsTopo(fixedSixthRingInside)).isTrue());
        assertThat(loaded.approvedForProduction()).isFalse();
    }

    private static Geometry featureGeometry(JsonNode root, String role) {
        for (JsonNode feature : root.path("features")) {
            if (role.equals(feature.path("properties").path("role").asText())) {
                return SixthRingBoundaryLoader.polygonalGeometry(feature.path("geometry"));
            }
        }
        throw new IllegalStateException("missing boundary feature role=" + role);
    }

    private static JsonNode findTongzhouFeature(JsonNode root) {
        for (JsonNode feature : root.path("features")) {
            JsonNode properties = feature.path("properties");
            String geometryType = feature.path("geometry").path("type").asText();
            if ("Q393836".equals(properties.path("wikidata").asText())
                    && "6".equals(properties.path("admin_level").asText())
                    && ("Polygon".equals(geometryType) || "MultiPolygon".equals(geometryType))) {
                return feature;
            }
        }
        throw new IllegalStateException("Tongzhou relation r2988902 polygon was not exported");
    }

    private static void writeCandidate(
            ObjectMapper objectMapper,
            Path output,
            Path sixthRingSource,
            Path tongzhouSource,
            String pbfHash,
            Geometry controlledArea,
            Geometry sixthRingArea,
            Geometry tongzhouArea) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("coordinateSystem", "WGS84");
        root.put("schemaVersion", 3);
        root.put("boundaryVersion", "controlled-area-v2");
        root.put("candidateOnly", true);
        root.put("approvedForProduction", false);
        root.put("generatedAt", Instant.now().toString());
        root.put("sourcePbfSha256", pbfHash);
        root.put("sixthRingRelationId", 295982);
        root.put("tongzhouRelationId", 2988902);
        root.put("sixthRingSource", sixthRingSource.toString());
        root.put("sixthRingSourceSha256", Hashing.sha256(sixthRingSource));
        root.put("tongzhouSource", tongzhouSource.toString());
        root.put("tongzhouSourceSha256", Hashing.sha256(tongzhouSource));
        root.put("boundaryRule", "union(sixth-ring inner carriageway polygon, Tongzhou r2988902)");

        ObjectNode feature = root.putArray("features").addObject();
        feature.put("type", "Feature");
        ObjectNode properties = feature.putObject("properties");
        properties.put("role", "controlled_area");
        properties.put("sixthRingRelationId", 295982);
        properties.put("tongzhouRelationId", 2988902);
        feature.set("geometry", geometryNode(objectMapper, controlledArea));

        ObjectNode sixthRingFeature = (ObjectNode) root.withArray("features").addObject();
        sixthRingFeature.put("type", "Feature");
        ObjectNode sixthRingProperties = sixthRingFeature.putObject("properties");
        sixthRingProperties.put("role", "sixth_ring_area");
        sixthRingProperties.put("osmRelationId", 295982);
        sixthRingFeature.set("geometry", geometryNode(objectMapper, sixthRingArea));

        ObjectNode tongzhouFeature = (ObjectNode) root.withArray("features").addObject();
        tongzhouFeature.put("type", "Feature");
        ObjectNode tongzhouProperties = tongzhouFeature.putObject("properties");
        tongzhouProperties.put("role", "tongzhou_area");
        tongzhouProperties.put("osmRelationId", 2988902);
        tongzhouFeature.set("geometry", geometryNode(objectMapper, tongzhouArea));

        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }

    private static ObjectNode geometryNode(ObjectMapper objectMapper, Geometry geometry) {
        ObjectNode node = objectMapper.createObjectNode();
        if (geometry instanceof Polygon polygon) {
            node.put("type", "Polygon");
            node.set("coordinates", polygonCoordinates(objectMapper, polygon));
            return node;
        }
        if (geometry instanceof MultiPolygon multiPolygon) {
            node.put("type", "MultiPolygon");
            ArrayNode polygons = node.putArray("coordinates");
            for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
                polygons.add(polygonCoordinates(
                        objectMapper, (Polygon) multiPolygon.getGeometryN(index)));
            }
            return node;
        }
        throw new IllegalArgumentException("controlled area is not polygonal");
    }

    private static ArrayNode polygonCoordinates(ObjectMapper objectMapper, Polygon polygon) {
        ArrayNode rings = objectMapper.createArrayNode();
        rings.add(coordinates(objectMapper, polygon.getExteriorRing().getCoordinates()));
        for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
            rings.add(coordinates(
                    objectMapper, polygon.getInteriorRingN(index).getCoordinates()));
        }
        return rings;
    }

    private static ArrayNode coordinates(ObjectMapper objectMapper, Coordinate[] coordinates) {
        ArrayNode values = objectMapper.createArrayNode();
        for (Coordinate coordinate : coordinates) {
            ArrayNode value = values.addArray();
            value.add(coordinate.x);
            value.add(coordinate.y);
        }
        return values;
    }

    private static void writeReport(
            Path output,
            Path candidate,
            Path sixthRingSource,
            Path tongzhouSource,
            String pbfHash,
            Geometry sixthRing,
            Geometry tongzhou,
            Geometry union) throws Exception {
        String report = """
                # 六环与通州并集边界候选报告

                - 生成时间：%s
                - 当前状态：候选，未批准用于生产
                - 源 PBF SHA-256：`%s`
                - 六环内侧面来源：`%s`
                - 通州行政区来源：`%s`
                - 候选边界：`%s`

                | 检查项 | 结果 |
                |---|---:|
                | 六环几何类型 | %s |
                | 通州几何类型 | %s |
                | 并集几何类型 | %s |
                | 并集组成面数 | %d |
                | 并集有效 | %s |
                | 六环内侧面未覆盖面积（平方度） | %.12g |
                | 通州行政区未覆盖面积（平方度） | %.12g |
                | 六环内侧面与通州相交 | %s |
                | 边界坐标数 | %d |

                此报告只证明几何生成和拓扑基本条件。上线前仍需地图叠加抽查、GraphHopper 通行口审计和真实路线回归。
                """.formatted(
                Instant.now(), pbfHash, sixthRingSource, tongzhouSource, candidate,
                sixthRing.getGeometryType(), tongzhou.getGeometryType(), union.getGeometryType(),
                union.getNumGeometries(), union.isValid(),
                sixthRing.difference(union).getArea(), tongzhou.difference(union).getArea(),
                sixthRing.intersects(tongzhou),
                union.getBoundary().getNumPoints());
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
    }
}
