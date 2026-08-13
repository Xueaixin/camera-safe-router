package cn.camera.safe.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public final class SixthRingBoundaryLoader {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final ObjectMapper objectMapper;

    public SixthRingBoundaryLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LoadedBoundary load(
            Path input,
            String expectedPbfSha256,
            boolean requireApprovedBoundary) throws IOException {
        Path normalized = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalStateException("六环边界文件不存在: " + normalized);
        }

        JsonNode root = objectMapper.readTree(normalized.toFile());
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IllegalStateException("六环边界文件必须是 GeoJSON FeatureCollection");
        }
        if (!"WGS84".equals(root.path("coordinateSystem").asText())) {
            throw new IllegalStateException("六环边界文件坐标系必须是 WGS84");
        }
        String sourcePbfSha256 = root.path("sourcePbfSha256").asText();
        if (!sourcePbfSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("六环边界文件缺少有效的源 PBF SHA-256");
        }
        if (!sourcePbfSha256.equalsIgnoreCase(expectedPbfSha256)) {
            throw new IllegalStateException("六环边界文件与当前路网 PBF 不匹配");
        }

        boolean approvedForProduction = root.path("approvedForProduction").asBoolean(false);
        if (requireApprovedBoundary && !approvedForProduction) {
            throw new IllegalStateException("六环边界尚未获准用于生产");
        }

        Geometry controlledArea = null;
        Geometry sixthRingArea = null;
        Geometry tongzhouArea = null;
        Geometry provincialBorder = null;
        Polygon legacyInside = null;
        for (JsonNode feature : root.path("features")) {
            String role = feature.path("properties").path("role").asText();
            JsonNode geometry = feature.path("geometry");
            if ("controlled_area".equals(role)) {
                if (controlledArea != null) {
                    throw new IllegalStateException("受控区边界文件包含重复的 controlled_area");
                }
                controlledArea = polygonalGeometry(geometry);
            } else if ("sixth_ring_area".equals(role)) {
                if (sixthRingArea != null) {
                    throw new IllegalStateException("受控区边界文件包含重复的 sixth_ring_area");
                }
                sixthRingArea = polygonalGeometry(geometry);
            } else if ("tongzhou_area".equals(role)) {
                if (tongzhouArea != null) {
                    throw new IllegalStateException("受控区边界文件包含重复的 tongzhou_area");
                }
                tongzhouArea = polygonalGeometry(geometry);
            } else if ("provincial_border".equals(role)) {
                if (provincialBorder != null) {
                    throw new IllegalStateException(
                            "controlled-area boundary file contains duplicate provincial_border");
                }
                provincialBorder = lineGeometry(geometry);
            } else if ("inside_boundary".equals(role)) {
                if (legacyInside != null) {
                    throw new IllegalStateException("旧版六环边界文件包含重复的 inside_boundary");
                }
                legacyInside = polygon(geometry);
            }
        }
        if (root.path("schemaVersion").asInt(1) >= 4 && provincialBorder == null) {
            throw new IllegalStateException(
                    "schema v4 controlled-area boundary must contain provincial_border");
        }
        if (controlledArea == null) {
            controlledArea = legacyInside;
        }
        if (root.path("schemaVersion").asInt(1) >= 2 && tongzhouArea == null) {
            throw new IllegalStateException("第二版受控区边界文件必须包含 tongzhou_area 辅助要素");
        }
        if (root.path("schemaVersion").asInt(1) >= 3 && sixthRingArea == null) {
            throw new IllegalStateException("第三版受控区边界文件必须包含 sixth_ring_area 辅助要素");
        }
        if (controlledArea == null) {
            throw new IllegalStateException(
                    "受控区边界文件必须包含 controlled_area；迁移期旧文件至少包含 inside_boundary");
        }

        String version = "sha256:" + Hashing.sha256(normalized);
        return new LoadedBoundary(
                new SixthRingBoundary(
                        controlledArea, sixthRingArea, tongzhouArea, provincialBorder, version),
                sourcePbfSha256.toLowerCase(),
                approvedForProduction,
                normalized);
    }

    static Geometry lineGeometry(JsonNode geometry) {
        String type = geometry.path("type").asText();
        JsonNode coordinates = geometry.path("coordinates");
        return switch (type) {
            case "LineString" -> GEOMETRY_FACTORY.createLineString(lineCoordinates(coordinates));
            case "MultiLineString" -> multiLine(coordinates);
            default -> throw new IllegalStateException(
                    "provincial border geometry must be LineString or MultiLineString");
        };
    }

    private static org.locationtech.jts.geom.MultiLineString multiLine(JsonNode lines) {
        if (!lines.isArray() || lines.isEmpty()) {
            throw new IllegalStateException("provincial border MultiLineString coordinates empty");
        }
        org.locationtech.jts.geom.LineString[] values =
                new org.locationtech.jts.geom.LineString[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            values[index] = GEOMETRY_FACTORY.createLineString(lineCoordinates(lines.get(index)));
        }
        return GEOMETRY_FACTORY.createMultiLineString(values);
    }

    private static Coordinate[] lineCoordinates(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.size() < 2) {
            throw new IllegalStateException("provincial border LineString coordinates invalid");
        }
        List<Coordinate> values = new ArrayList<>();
        for (JsonNode coordinate : coordinates) {
            if (!coordinate.isArray() || coordinate.size() < 2) {
                throw new IllegalStateException("provincial border LineString coordinates invalid");
            }
            values.add(new Coordinate(coordinate.get(0).asDouble(), coordinate.get(1).asDouble()));
        }
        return values.toArray(Coordinate[]::new);
    }

    static Geometry polygonalGeometry(JsonNode geometry) {
        String type = geometry.path("type").asText();
        JsonNode coordinates = geometry.path("coordinates");
        return switch (type) {
            case "Polygon" -> polygonCoordinates(coordinates);
            case "MultiPolygon" -> multiPolygon(coordinates);
            default -> throw new IllegalStateException(
                    "受控区边界几何必须是 Polygon 或 MultiPolygon");
        };
    }

    private static Polygon polygon(JsonNode geometry) {
        if (!"Polygon".equals(geometry.path("type").asText())) {
            throw new IllegalStateException("旧版六环边界要素必须是 Polygon");
        }
        return polygonCoordinates(geometry.path("coordinates"));
    }

    private static Polygon polygonCoordinates(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new IllegalStateException("受控区 Polygon 坐标为空");
        }
        LinearRing shell = linearRing(rings.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int index = 1; index < rings.size(); index++) {
            holes[index - 1] = linearRing(rings.get(index));
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static MultiPolygon multiPolygon(JsonNode polygons) {
        if (!polygons.isArray() || polygons.isEmpty()) {
            throw new IllegalStateException("受控区 MultiPolygon 坐标为空");
        }
        Polygon[] values = new Polygon[polygons.size()];
        for (int index = 0; index < polygons.size(); index++) {
            values[index] = polygonCoordinates(polygons.get(index));
        }
        return GEOMETRY_FACTORY.createMultiPolygon(values);
    }

    private static LinearRing linearRing(JsonNode coordinates) {
        List<Coordinate> values = new ArrayList<>();
        for (JsonNode coordinate : coordinates) {
            if (!coordinate.isArray() || coordinate.size() < 2) {
                throw new IllegalStateException("六环边界坐标格式无效");
            }
            values.add(new Coordinate(coordinate.get(0).asDouble(), coordinate.get(1).asDouble()));
        }
        return GEOMETRY_FACTORY.createLinearRing(values.toArray(Coordinate[]::new));
    }

    public record LoadedBoundary(
            SixthRingBoundary boundary,
            String sourcePbfSha256,
            boolean approvedForProduction,
            Path sourcePath) {
    }
}
