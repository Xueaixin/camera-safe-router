package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadAccess;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.CustomModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RoutingFoundationPocTest {

    @Test
    void reportsCurrentTurnAccessAndWeightingConfiguration() throws Exception {
        String configuredPbf = System.getProperty("real.pbf");
        String configuredGraphCache = System.getProperty("real.graph.cache");
        String configuredReport = System.getProperty("routing.foundation.report.output");
        assumeTrue(configuredPbf != null && !configuredPbf.isBlank()
                        && configuredGraphCache != null && !configuredGraphCache.isBlank()
                        && configuredReport != null && !configuredReport.isBlank(),
                "Set real.pbf, real.graph.cache and routing.foundation.report.output to run this POC");

        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        Path graphCache = Path.of(configuredGraphCache).toAbsolutePath().normalize();
        Path report = Path.of(configuredReport).toAbsolutePath().normalize();
        GraphHopperManager manager = new GraphHopperManager(properties(pbf, graphCache));
        manager.initialize();
        try {
            HardAvoidingGraphHopper hopper = manager.requireHopper();
            Profile profile = hopper.getProfile("car");
            CustomModel customModel = profile.getCustomModel();
            boolean roadAccessReferenced = java.util.stream.Stream.concat(
                            customModel.getPriority().stream(), customModel.getSpeed().stream())
                    .map(Object::toString)
                    .anyMatch(statement -> statement.contains("road_access"));
            boolean turnEncodingPresent = !hopper.getEncodingManager().getTurnEncodedValues().isEmpty();
            Map<RoadAccess, Long> roadAccessCounts = roadAccessCounts(hopper);

            assertThat(profile.hasTurnCosts()).as("current car profile turn costs").isFalse();
            assertThat(turnEncodingPresent).as("turn encoded values in the current cache").isFalse();
            assertThat(customModel.getDistanceInfluence()).isEqualTo(90.0);
            assertThat(roadAccessReferenced).as("road_access used by current car custom model").isFalse();
            assertThat(roadAccessCounts.getOrDefault(RoadAccess.PRIVATE, 0L)).isPositive();
            assertThat(roadAccessCounts.getOrDefault(RoadAccess.DESTINATION, 0L)).isPositive();

            writeReport(
                    report,
                    manager.requireGraphFingerprint(),
                    profile,
                    customModel,
                    turnEncodingPresent,
                    roadAccessReferenced,
                    roadAccessCounts);
        } finally {
            manager.close();
        }
    }

    private static Map<RoadAccess, Long> roadAccessCounts(HardAvoidingGraphHopper hopper) {
        BaseGraph graph = hopper.getBaseGraph();
        BooleanEncodedValue carAccess = hopper.getEncodingManager().getBooleanEncodedValue("car_access");
        EnumEncodedValue<RoadAccess> roadAccess = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadAccess.KEY, RoadAccess.class);
        Map<RoadAccess, Long> counts = new EnumMap<>(RoadAccess.class);
        AllEdgesIterator edge = graph.getAllEdges();
        while (edge.next()) {
            if (edge.get(carAccess)) {
                counts.merge(edge.get(roadAccess), 1L, Long::sum);
            }
            if (edge.getReverse(carAccess)) {
                counts.merge(edge.getReverse(roadAccess), 1L, Long::sum);
            }
        }
        return Map.copyOf(counts);
    }

    private static void writeReport(
            Path output,
            String graphFingerprint,
            Profile profile,
            CustomModel customModel,
            boolean turnEncodingPresent,
            boolean roadAccessReferenced,
            Map<RoadAccess, Long> roadAccessCounts) throws Exception {
        StringBuilder report = new StringBuilder("# 路线搜索基础配置 POC 报告\n\n")
                .append("- 图指纹：`").append(graphFingerprint).append("`\n")
                .append("- Profile：`").append(profile.getName()).append("`\n\n")
                .append("## 当前结论\n\n")
                .append("| 检查项 | 当前结果 |\n|---|---|\n")
                .append("| Profile 启用 turn costs | ").append(profile.hasTurnCosts()).append(" |\n")
                .append("| 图缓存包含 turn encoded values | ").append(turnEncodingPresent).append(" |\n")
                .append("| `car.json` distance influence | ")
                .append(String.format(Locale.ROOT, "%.1f", customModel.getDistanceInfluence())).append(" |\n")
                .append("| `car.json` 引用 road_access | ").append(roadAccessReferenced).append(" |\n\n")
                .append("当前缓存没有导入可供搜索使用的转向限制；当前内置 `car.json` 是时间权重叠加每公里 90 秒的距离影响，不是距离最短权重；虽然图中编码了 `road_access`，当前模型没有使用它。三项都不满足正式六环算法的正确性门槛。\n\n")
                .append("## 可驾车有向遍历的 road_access 分布\n\n")
                .append("| road_access | 遍历数 |\n|---|---:|\n");
        for (RoadAccess access : RoadAccess.values()) {
            report.append("| ").append(access).append(" | ")
                    .append(roadAccessCounts.getOrDefault(access, 0L)).append(" |\n");
        }
        report.append("\n这些计数证明当前真实图中存在受限访问道路，不能把 `road_access` 未参与权重视为无影响。正式修改需要重建包含 turn costs 的图缓存，并使用显式的距离优先、访问权限合规模型。\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private static AppProperties properties(Path pbf, Path graphCache) {
        return new AppProperties(
                new AppProperties.Routing(
                        pbf.toString(), graphCache.toString(), 30, 2, 4,
                        Duration.ofSeconds(30), 2_000_000),
                new AppProperties.Cameras(
                        pbf.toString(), graphCache.resolve("poc-snapshots").toString(),
                        true, 10_000, 1, updateProperties()),
                new AppProperties.Admin(true));
    }
}
