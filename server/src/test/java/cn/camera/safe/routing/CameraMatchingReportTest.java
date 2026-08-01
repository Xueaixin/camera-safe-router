package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.camera.CameraLoadResult;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CameraMatchingReportTest {
    private static final double REPORT_RADIUS_METERS = 30;
    private static final double NEAREST_ROAD_SEARCH_METERS = 1_000;

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesChineseCameraMatchingReport() throws Exception {
        String configuredPbf = System.getProperty("report.pbf");
        String configuredCameras = System.getProperty("report.camera.json");
        String configuredOutput = System.getProperty("report.output");
        assumeTrue(configuredPbf != null && !configuredPbf.isBlank()
                        && configuredCameras != null && !configuredCameras.isBlank()
                        && configuredOutput != null && !configuredOutput.isBlank(),
                "Set report.pbf, report.camera.json and report.output to generate the report");

        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        Path cameraJson = Path.of(configuredCameras).toAbsolutePath().normalize();
        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();
        ObjectMapper objectMapper = new ObjectMapper();
        AppProperties properties = properties(pbf, cameraJson);
        GraphHopperManager graphManager = new GraphHopperManager(properties);
        graphManager.initialize();
        try {
            CameraJsonLoader loader = new CameraJsonLoader(objectMapper, new CoordinateConverter());
            CameraLoadResult loaded = loader.load(cameraJson);
            assertThat(loaded.isValid()).isTrue();
            RawEligibility rawEligibility = readEligibility(objectMapper, cameraJson);
            assertThat(loaded.sourceRecordCount()).isEqualTo(rawEligibility.sourceCount());
            assertThat(loaded.retainedRecordCount()).isEqualTo(rawEligibility.retainedIds().size());
            assertThat(loaded.outsideSixRingRecordCount()).isEqualTo(rawEligibility.excluded());
            assertThat(loaded.unrecognizedSixRingOutRecordCount())
                    .isEqualTo(rawEligibility.missingOrInvalid());
            CameraSnapshot eligibleCameras = CameraSnapshot.from(loaded);
            List<CameraPoint> eligiblePoints = eligibleCameras.cameras();
            CameraSnapshot allCameras = loadAllForComparison(objectMapper, loader, cameraJson);

            RoadEdgeIndex roadIndex = graphManager.requireRoadEdgeIndex();
            BlockedEdgeGenerator generator = new BlockedEdgeGenerator();
            BlockedEdgeBuildResult radius20 = generator.generate(allCameras, roadIndex, 20);
            BlockedEdgeBuildResult radius30 = generator.generate(allCameras, roadIndex, 30);
            BlockedEdgeBuildResult radius50 = generator.generate(allCameras, roadIndex, 50);
            BlockedEdgeBuildResult eligible30 = generator.generate(
                    eligibleCameras, roadIndex, REPORT_RADIUS_METERS);

            List<MatchDetail> allDetails = classify(
                    allCameras.cameras(), radius30.unmatchedCameraIds(), graphManager, roadIndex);
            List<MatchDetail> eligibleDetails = classify(
                    eligibleCameras.cameras(), eligible30.unmatchedCameraIds(), graphManager, roadIndex);
            String report = report(
                    pbf,
                    cameraJson,
                    graphManager,
                    rawEligibility,
                    radius20,
                    radius30,
                    radius50,
                    eligible30,
                    allDetails,
                    eligibleDetails);
            Files.createDirectories(output.getParent());
            Files.writeString(output, report, StandardCharsets.UTF_8);
            assertThat(output).isRegularFile();
            assertThat(eligiblePoints).hasSize(rawEligibility.retainedIds().size());
            System.out.printf(
                    "CAMERA_MATCHING_REPORT output=%s allMatched=%d allUnmatched=%d "
                            + "eligible=%d eligibleMatched=%d eligibleUnmatched=%d%n",
                    output,
                    radius30.matchedCameraCount(),
                    radius30.unmatchedCameraIds().size(),
                    eligiblePoints.size(),
                    eligible30.matchedCameraCount(),
                    eligible30.unmatchedCameraIds().size());
        } finally {
            graphManager.close();
        }
    }

    private CameraSnapshot loadAllForComparison(
            ObjectMapper objectMapper,
            CameraJsonLoader loader,
            Path source) throws Exception {
        JsonNode root = objectMapper.readTree(Files.readAllBytes(source));
        JsonNode records = root.isArray() ? root : firstArray(root);
        for (JsonNode record : records) {
            if (record.isObject()) {
                ((ObjectNode) record).put("IsSixRingOut", "0");
            }
        }
        Path comparisonSource = temporaryDirectory.resolve("all-cameras-for-report.json");
        objectMapper.writeValue(comparisonSource.toFile(), root);
        CameraLoadResult allRecords = loader.load(comparisonSource);
        assertThat(allRecords.isValid()).isTrue();
        assertThat(allRecords.cameras()).hasSize(records.size());
        return CameraSnapshot.from(allRecords);
    }

    private AppProperties properties(Path pbf, Path cameras) {
        return new AppProperties(
                new AppProperties.Routing(
                        pbf.toString(),
                        temporaryDirectory.resolve("graph-cache").toString(),
                        REPORT_RADIUS_METERS,
                        2,
                        4,
                        Duration.ofSeconds(10),
                        1_000_000),
                new AppProperties.Cameras(
                        cameras.toString(),
                        temporaryDirectory.resolve("snapshots").toString(),
                        true,
                        10_000,
                        1,
                        updateProperties()),
                new AppProperties.Admin(true));
    }

    private static RawEligibility readEligibility(ObjectMapper objectMapper, Path source) throws Exception {
        JsonNode root = objectMapper.readTree(Files.readAllBytes(source));
        JsonNode records = root.isArray() ? root : firstArray(root);
        Set<String> retainedIds = new HashSet<>();
        Map<String, String> valuesById = new HashMap<>();
        int excluded = 0;
        int missingOrInvalid = 0;
        for (JsonNode record : records) {
            String id = record.path("Id").asText("");
            JsonNode value = record.get("IsSixRingOut");
            String normalized = value == null || value.isNull()
                    ? "<缺失>"
                    : value.isTextual() ? value.textValue() : "<非字符串:" + value + ">";
            valuesById.put(id, normalized);
            if (value != null && ((value.isTextual() && "1".equals(value.textValue()))
                    || (value.isNumber() && value.decimalValue().compareTo(java.math.BigDecimal.ONE) == 0))) {
                excluded++;
            } else {
                retainedIds.add(id);
                boolean recognizedInside = value != null
                        && ((value.isTextual() && "0".equals(value.textValue()))
                        || (value.isNumber()
                        && value.decimalValue().compareTo(java.math.BigDecimal.ZERO) == 0));
                if (!recognizedInside) {
                    missingOrInvalid++;
                }
            }
        }
        return new RawEligibility(records.size(), retainedIds, excluded, missingOrInvalid, valuesById);
    }

    private static JsonNode firstArray(JsonNode root) {
        if (root != null && root.isObject()) {
            for (String name : List.of("data", "Data", "items", "Items", "result", "Result")) {
                JsonNode candidate = root.get(name);
                if (candidate != null && candidate.isArray()) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("camera JSON does not contain a supported array");
    }

    private static List<MatchDetail> classify(
            List<CameraPoint> cameras,
            List<String> unmatchedIds,
            GraphHopperManager graphManager,
            RoadEdgeIndex roadIndex) {
        Set<String> unmatched = Set.copyOf(unmatchedIds);
        return cameras.stream()
                .map(camera -> {
                    boolean withinBounds = graphManager.requireHopper().getBaseGraph()
                            .getBounds().contains(camera.wgs84().lat(), camera.wgs84().lng());
                    if (!unmatched.contains(camera.id())) {
                        return new MatchDetail(camera, true, withinBounds, null);
                    }
                    Double nearest = nearestRoadMeters(camera, roadIndex);
                    return new MatchDetail(camera, false, withinBounds, nearest);
                })
                .toList();
    }

    private static Double nearestRoadMeters(CameraPoint camera, RoadEdgeIndex roadIndex) {
        double minimum = roadIndex.candidates(camera.wgs84(), NEAREST_ROAD_SEARCH_METERS).stream()
                .mapToDouble(edge -> GeoDistance.minimumMeters(camera.wgs84(), roadIndex.geometry(edge)))
                .min()
                .orElse(Double.POSITIVE_INFINITY);
        return Double.isFinite(minimum) ? minimum : null;
    }

    private static String report(
            Path pbf,
            Path cameraJson,
            GraphHopperManager graphManager,
            RawEligibility eligibility,
            BlockedEdgeBuildResult radius20,
            BlockedEdgeBuildResult radius30,
            BlockedEdgeBuildResult radius50,
            BlockedEdgeBuildResult eligible30,
            List<MatchDetail> allDetails,
            List<MatchDetail> eligibleDetails) throws Exception {
        long allInside = allDetails.stream().filter(MatchDetail::withinBounds).count();
        long allOutside = allDetails.size() - allInside;
        long allUnmatchedInside = allDetails.stream()
                .filter(detail -> !detail.matched() && detail.withinBounds()).count();
        long allUnmatchedOutside = radius30.unmatchedCameraIds().size() - allUnmatchedInside;
        long eligibleInside = eligibleDetails.stream().filter(MatchDetail::withinBounds).count();
        long eligibleOutside = eligibleDetails.size() - eligibleInside;
        long eligibleUnmatchedInside = eligibleDetails.stream()
                .filter(detail -> !detail.matched() && detail.withinBounds()).count();
        long eligibleUnmatchedOutside = eligible30.unmatchedCameraIds().size() - eligibleUnmatchedInside;

        StringBuilder report = new StringBuilder()
                .append("# 新路网摄像头匹配验证报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append("\n")
                .append("- 坐标假设：摄像头原始坐标按 GCJ-02 转为 WGS84\n")
                .append("- 默认匹配半径：30 米\n")
                .append("- 说明：自动匹配结果不替代高德/OSM人工道路层级核对。\n\n")
                .append("## 1. 输入与路网\n\n")
                .append("| 项目 | 结果 |\n|---|---:|\n")
                .append("| PBF 文件 | `").append(escape(pbf.toString())).append("` |\n")
                .append("| PBF 大小 | ").append(Files.size(pbf)).append(" 字节 |\n")
                .append("| PBF SHA-256 | `").append(Hashing.sha256(pbf)).append("` |\n")
                .append("| 摄像头 JSON | `").append(escape(cameraJson.toString())).append("` |\n")
                .append("| 摄像头 JSON SHA-256 | `").append(Hashing.sha256(cameraJson)).append("` |\n")
                .append("| GraphHopper 节点 | ")
                .append(graphManager.requireHopper().getBaseGraph().getNodes()).append(" |\n")
                .append("| GraphHopper 基础边 | ")
                .append(graphManager.requireHopper().getBaseGraph().getEdges()).append(" |\n")
                .append("| 可驾车索引边 | ")
                .append(graphManager.requireRoadEdgeIndex().indexedEdgeCount()).append(" |\n")
                .append("| 图边界 | `")
                .append(escape(graphManager.requireHopper().getBaseGraph().getBounds().toString()))
                .append("` |\n\n")
                .append("## 2. 全量 ").append(allDetails.size()).append(" 条对照结果\n\n")
                .append("| 半径 | 匹配 | 未匹配 | 双向禁行基础边 |\n|---:|---:|---:|---:|\n");
        appendRadius(report, 20, radius20);
        appendRadius(report, 30, radius30);
        appendRadius(report, 50, radius50);
        report.append("\n全量点位中，图边界内 ").append(allInside)
                .append(" 条，图边界外 ").append(allOutside)
                .append(" 条；30 米未匹配点中，边界内 ").append(allUnmatchedInside)
                .append(" 条，边界外 ").append(allUnmatchedOutside).append(" 条。\n\n")
                .append("## 3. `IsSixRingOut != 1` 正式口径\n\n")
                .append("| 项目 | 数量 |\n|---|---:|\n")
                .append("| 源记录 | ").append(eligibility.sourceCount()).append(" |\n")
                .append("| 纳入：`IsSixRingOut != 1` | ")
                .append(eligibility.retainedIds().size()).append(" |\n")
                .append("| 排除：`IsSixRingOut = 1` | ").append(eligibility.excluded()).append(" |\n")
                .append("| 纳入但标记异常：字段缺失或非法 | ")
                .append(eligibility.missingOrInvalid()).append(" |\n")
                .append("| 30 米匹配 | ").append(eligible30.matchedCameraCount()).append(" |\n")
                .append("| 30 米未匹配 | ").append(eligible30.unmatchedCameraIds().size()).append(" |\n")
                .append("| 双向禁行基础边 | ").append(eligible30.snapshot().blockedEdgeCount()).append(" |\n")
                .append("| 图边界内 | ").append(eligibleInside).append(" |\n")
                .append("| 图边界外 | ").append(eligibleOutside).append(" |\n")
                .append("| 未匹配且边界内 | ").append(eligibleUnmatchedInside).append(" |\n")
                .append("| 未匹配且边界外 | ").append(eligibleUnmatchedOutside).append(" |\n\n")
                .append("## 4. 全量口径30米未匹配明细\n\n");
        appendUnmatchedTable(report, allDetails, eligibility.valuesById());
        report.append("\n## 5. 生产保留口径30米未匹配明细\n\n");
        appendUnmatchedTable(report, eligibleDetails, eligibility.valuesById());
        report.append("\n## 6. 结论边界\n\n")
                .append("- 匹配表示点位30米范围内至少存在一条可驾车基础边，不代表主辅路、高架层级一定正确。\n")
                .append("- 图边界外点位无法参与当前 PBF 内的路线搜索。\n")
                .append("- 范围内未匹配点应优先人工检查坐标、道路缺失和离路距离。\n")
                .append("- 生产快照加载器只排除语义为 `IsSixRingOut = 1` 的记录；缺失或非法值保留并计数。\n");
        return report.toString();
    }

    private static void appendRadius(
            StringBuilder report, int radius, BlockedEdgeBuildResult result) {
        report.append("| ").append(radius).append(" 米 | ")
                .append(result.matchedCameraCount()).append(" | ")
                .append(result.unmatchedCameraIds().size()).append(" | ")
                .append(result.snapshot().blockedEdgeCount()).append(" |\n");
    }

    private static void appendUnmatchedTable(
            StringBuilder report,
            List<MatchDetail> details,
            Map<String, String> eligibilityValues) {
        List<MatchDetail> unmatched = details.stream().filter(detail -> !detail.matched()).toList();
        if (unmatched.isEmpty()) {
            report.append("没有未匹配点。\n");
            return;
        }
        report.append("| ID | IsSixRingOut | 图边界内 | 最近可驾车道路距离 | 地址 | GCJ-02 | WGS84 |\n")
                .append("|---|---:|---|---:|---|---|---|\n");
        for (MatchDetail detail : unmatched) {
            CameraPoint camera = detail.camera();
            String nearest = detail.nearestRoadMeters() == null
                    ? "> 1000 米或无候选"
                    : String.format(Locale.ROOT, "%.1f 米", detail.nearestRoadMeters());
            report.append("| ").append(escape(camera.id())).append(" | ")
                    .append(escape(eligibilityValues.getOrDefault(camera.id(), "<未知>"))).append(" | ")
                    .append(detail.withinBounds() ? "是" : "否").append(" | ")
                    .append(nearest).append(" | ")
                    .append(escape(camera.address())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.7f, %.7f", camera.gcj02().lng(), camera.gcj02().lat()))
                    .append(" | ")
                    .append(String.format(Locale.ROOT, "%.7f, %.7f", camera.wgs84().lng(), camera.wgs84().lat()))
                    .append(" |\n");
        }
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private record RawEligibility(
            int sourceCount,
            Set<String> retainedIds,
            int excluded,
            int missingOrInvalid,
            Map<String, String> valuesById) {
    }

    private record MatchDetail(
            CameraPoint camera,
            boolean matched,
            boolean withinBounds,
            Double nearestRoadMeters) {
    }
}
