package cn.camera.safe.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.operation.linemerge.LineMerger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Command-line oriented generator for the controlled-area boundary.
 *
 * <p>Inputs are the sixth-ring carriageway lines (LineString features exported
 * from the OSM relation r295982) and the Tongzhou administrative polygon
 * (exported from relation r2988902). The inner carriageway loop becomes the
 * sixth-ring area, then {@code union} with Tongzhou produces the controlled
 * area in schema v3 GeoJSON form.
 */
public final class ControlledAreaBoundaryGenerator {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private ControlledAreaBoundaryGenerator() {
    }

    /** Builds the sixth-ring inner carriageway polygon from the exported lines. */
    public static Geometry sixthRingInsideFromLines(JsonNode linesRoot) {
        List<LineString> sourceLines = new ArrayList<>();
        LineMerger merger = new LineMerger();
        for (JsonNode feature : linesRoot.path("features")) {
            JsonNode geometry = feature.path("geometry");
            if (!"LineString".equals(geometry.path("type").asText())) {
                continue;
            }
            LineString line = lineString(geometry.path("coordinates"));
            if (line.getNumPoints() < 2 || line.isEmpty()) {
                continue;
            }
            sourceLines.add(line);
            merger.add(line);
        }
        @SuppressWarnings("unchecked")
        Collection<LineString> merged = merger.getMergedLineStrings();
        List<LineString> closedRings = merged.stream()
                .filter(LineString::isClosed)
                .filter(line -> line.getNumPoints() >= 4)
                .toList();
        List<Polygon> polygons = closedRings.stream()
                .map(line -> GEOMETRY_FACTORY.createPolygon(line.getCoordinates()))
                .filter(Polygon::isValid)
                .sorted(Comparator.comparingDouble(Polygon::getArea))
                .toList();
        if (polygons.size() < 2) {
            throw new IllegalStateException(
                    "sixth-ring lines must form at least two valid closed polygons, found "
                            + polygons.size() + " (source lines=" + sourceLines.size() + ")");
        }
        return polygons.get(polygons.size() - 2);
    }

    /** Extracts the Tongzhou administrative polygon from the exported relation file. */
    public static Geometry tongzhouPolygon(JsonNode tongzhouRoot) {
        for (JsonNode feature : tongzhouRoot.path("features")) {
            JsonNode properties = feature.path("properties");
            String geometryType = feature.path("geometry").path("type").asText();
            if ("Q393836".equals(properties.path("wikidata").asText())
                    && "6".equals(properties.path("admin_level").asText())
                    && ("Polygon".equals(geometryType) || "MultiPolygon".equals(geometryType))) {
                return GeometryFixer.fix(SixthRingBoundaryLoader.polygonalGeometry(
                        feature.path("geometry")));
            }
        }
        throw new IllegalStateException("Tongzhou relation r2988902 polygon was not exported");
    }

    /**
     * Unions the sixth-ring inner area with Tongzhou and writes the schema v3
     * candidate (or approved) boundary file.
     */
    public static Geometry generateAndWrite(
            ObjectMapper objectMapper,
            JsonNode sixthRingLines,
            JsonNode tongzhouRoot,
            Path output,
            Path sixthRingSource,
            Path tongzhouSource,
            String pbfHash,
            boolean approvedForProduction) throws IOException {
        Geometry sixthRingArea = GeometryFixer.fix(sixthRingInsideFromLines(sixthRingLines));
        Geometry tongzhouArea = tongzhouPolygon(tongzhouRoot);
        Geometry controlledArea = GeometryFixer.fix(sixthRingArea.union(tongzhouArea));
        if (!(controlledArea instanceof Polygon || controlledArea instanceof MultiPolygon)
                || controlledArea.isEmpty() || !controlledArea.isValid()) {
            throw new IllegalStateException("controlled area is not a valid polygon");
        }
        if (sixthRingArea.difference(controlledArea).getArea() > 1e-12
                || tongzhouArea.difference(controlledArea).getArea() > 1e-12) {
            throw new IllegalStateException("union does not cover the input areas");
        }
        if (!sixthRingArea.intersects(tongzhouArea)) {
            throw new IllegalStateException("sixth-ring inner area does not intersect Tongzhou");
        }
        writeSchemaV3(
                objectMapper,
                output,
                sixthRingSource,
                tongzhouSource,
                pbfHash,
                controlledArea,
                sixthRingArea,
                tongzhouArea,
                approvedForProduction);
        return controlledArea;
    }

    private static void writeSchemaV3(
            ObjectMapper objectMapper,
            Path output,
            Path sixthRingSource,
            Path tongzhouSource,
            String pbfHash,
            Geometry controlledArea,
            Geometry sixthRingArea,
            Geometry tongzhouArea,
            boolean approvedForProduction) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("coordinateSystem", "WGS84");
        root.put("schemaVersion", 3);
        root.put("boundaryVersion", "controlled-area-v2");
        root.put("candidateOnly", !approvedForProduction);
        root.put("approvedForProduction", approvedForProduction);
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

        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
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

    private static LineString lineString(JsonNode coordinates) {
        List<Coordinate> values = new ArrayList<>();
        if (coordinates.isArray()) {
            for (JsonNode coordinate : coordinates) {
                if (coordinate.isArray() && coordinate.size() >= 2) {
                    values.add(new Coordinate(
                            coordinate.get(0).asDouble(), coordinate.get(1).asDouble()));
                }
            }
        }
        LineString line = GEOMETRY_FACTORY.createLineString(values.toArray(Coordinate[]::new));
        if (line.getNumPoints() < 2 || line.isEmpty()) {
            throw new IllegalArgumentException("invalid LineString coordinates");
        }
        return line;
    }

    /** Compact summary line used by CLI output and audits. */
    public static String summarize(Geometry controlledArea, Geometry sixthRingArea, Geometry tongzhouArea) {
        return String.format(Locale.ROOT,
                "controlled=%s sixthRing=%s tongzhou=%s",
                geometryType(controlledArea),
                geometryType(sixthRingArea),
                geometryType(tongzhouArea));
    }

    private static String geometryType(Geometry geometry) {
        if (geometry instanceof Polygon polygon) {
            return "Polygon(rings=" + (polygon.getNumInteriorRing() + 1) + ")";
        }
        if (geometry instanceof MultiPolygon multiPolygon) {
            return "MultiPolygon(parts=" + multiPolygon.getNumGeometries() + ")";
        }
        return geometry.getGeometryType();
    }
}
