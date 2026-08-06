package cn.camera.safe.coordinate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CoordinateControlPointReportTest {
    @Test
    void writesReadOnlyControlPointCandidatesForManualAmapAndOsmReview() throws Exception {
        String configuredJson = System.getProperty("control.camera.json");
        assumeTrue(configuredJson != null && !configuredJson.isBlank(),
                "Set -Dcontrol.camera.json=<path-to-camera.json> to generate the report");

        Path source = Path.of(configuredJson).toAbsolutePath().normalize();
        JsonNode root = new ObjectMapper().readTree(source.toFile());
        assumeTrue(root.isArray() && root.size() >= 20, "Expected the current top-level camera array");

        CoordinateConverter converter = new CoordinateConverter();
        StringBuilder report = new StringBuilder("# 摄像头坐标控制点候选\n\n")
                .append("本文件只生成候选点，不代表源坐标系已经确认。请按 `docs/reference/坐标系与接口约定.md` 在高德和 OSM 人工核对。\n\n")
                .append("| ID | 地址 | 原始经度 | 原始纬度 | GCJ-02 转 WGS84 经度 | GCJ-02 转 WGS84 纬度 | 高德结论 | OSM 结论 |\n")
                .append("|---|---|---:|---:|---:|---:|---|---|\n");
        for (int sample = 0; sample < 20; sample++) {
            JsonNode item = root.get(sample * (root.size() - 1) / 19);
            Gcj02Coordinate original = new Gcj02Coordinate(item.path("Lng").asDouble(), item.path("Lat").asDouble());
            Wgs84Coordinate converted = converter.toWgs84(original);
            report.append('|').append(escape(item.path("Id").asText()))
                    .append('|').append(escape(item.path("Address").asText()))
                    .append('|').append(original.lng())
                    .append('|').append(original.lat())
                    .append('|').append(converted.lng())
                    .append('|').append(converted.lat())
                    .append("|待人工核对|待人工核对|\n");
        }

        Path output = Path.of("target", "generated-coordinate-control-points.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println("CONTROL_POINT_REPORT=" + output.toAbsolutePath().normalize());
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
