package cn.camera.safe.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
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

        Polygon inner = null;
        Polygon outer = null;
        for (JsonNode feature : root.path("features")) {
            String role = feature.path("properties").path("role").asText();
            JsonNode geometry = feature.path("geometry");
            if (!"Polygon".equals(geometry.path("type").asText())) {
                throw new IllegalStateException("六环边界要素必须是 Polygon");
            }
            Polygon polygon = polygon(geometry.path("coordinates"));
            if ("inside_boundary".equals(role)) {
                if (inner != null) {
                    throw new IllegalStateException("六环边界文件包含重复的 inside_boundary");
                }
                inner = polygon;
            } else if ("outside_boundary".equals(role)) {
                if (outer != null) {
                    throw new IllegalStateException("六环边界文件包含重复的 outside_boundary");
                }
                outer = polygon;
            }
        }
        if (inner == null || outer == null) {
            throw new IllegalStateException("六环边界文件必须同时包含内侧和外侧闭环");
        }

        String version = "sha256:" + Hashing.sha256(normalized);
        return new LoadedBoundary(
                new SixthRingBoundary(inner, outer, version),
                sourcePbfSha256.toLowerCase(),
                approvedForProduction,
                normalized);
    }

    private static Polygon polygon(JsonNode rings) {
        if (!rings.isArray() || rings.isEmpty()) {
            throw new IllegalStateException("六环边界 Polygon 坐标为空");
        }
        LinearRing shell = linearRing(rings.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int index = 1; index < rings.size(); index++) {
            holes[index - 1] = linearRing(rings.get(index));
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
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
