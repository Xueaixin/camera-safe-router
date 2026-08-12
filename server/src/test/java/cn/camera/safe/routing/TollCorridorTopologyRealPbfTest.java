package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static cn.camera.safe.routing.TollCorridorTopology.Role.ENTRY;
import static cn.camera.safe.routing.TollCorridorTopology.Role.EXIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TollCorridorTopologyRealPbfTest {
    private static final long DAJUESI_ENTRY_NODE = 1638692210L;
    private static final long DAJUESI_EXIT_NODE = 1638692211L;
    private static final long JUNZHUANG_ENTRY_NODE = 1080698588L;
    private static final long JUNZHUANG_EXIT_NODE = 1930483073L;
    private static final Wgs84Coordinate HUAWEI_BEIJING_RESEARCH_INSTITUTE =
            new Wgs84Coordinate(116.1892394, 40.060838);
    private static final Wgs84Coordinate LONGYIYUAN_WUQING =
            new Wgs84Coordinate(117.072047516124, 39.31270167958216);
    private static final Wgs84Coordinate ZHUXINZHUANG_NANQU =
            new Wgs84Coordinate(116.30328606291512, 40.097249927365986);

    @Test
    void identifiesDirectedTollCorridorsAndRoutesHuaweiToLongyiyuan() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real.pbf, real.graph.cache, real.camera.json and "
                        + "sixth.ring.boundary.input to run the real toll corridor test");
        assertThat(Hashing.sha256(configuration.pbf()))
                .isEqualTo("2a1bcebc16586858bc116a67dd10b9e5191241bbbb096866d0d0a2538ca16131");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AppProperties appProperties = appProperties(configuration);
        SixthRingProperties sixthRingProperties = new SixthRingProperties(
                configuration.boundary().toString(),
                false,
                50,
                1000,
                100,
                100,
                2_000_000,
                Duration.ofSeconds(5),
                false,
                4);
        GraphHopperManager graphManager = new GraphHopperManager(appProperties);
        graphManager.initialize();
        try {
            SixthRingRoutingManager sixthRingManager = new SixthRingRoutingManager(
                    graphManager,
                    sixthRingProperties,
                    new SixthRingBoundaryLoader(objectMapper));
            sixthRingManager.initialize();
            SixthRingRoutingContext context = sixthRingManager.requireContext();
            TollCorridorTopology topology = context.tollCorridors();

            assertThat(topology.audit().entryCorridors()).isPositive();
            assertThat(topology.audit().exitCorridors()).isPositive();
            assertThat(hasCorridor(topology, DAJUESI_ENTRY_NODE, ENTRY))
                    .as("Dajuesi entrance toll corridor")
                    .isTrue();
            assertThat(hasCorridor(topology, DAJUESI_EXIT_NODE, EXIT))
                    .as("Dajuesi exit toll corridor")
                    .isTrue();
            assertThat(hasCorridor(topology, JUNZHUANG_ENTRY_NODE, ENTRY))
                    .as("Junzhuang entrance toll corridor")
                    .isTrue();
            assertThat(hasCorridor(topology, JUNZHUANG_EXIT_NODE, EXIT))
                    .as("Junzhuang exit toll corridor")
                    .isTrue();
            assertThat(southboundEntryCorridor(
                    topology, DAJUESI_ENTRY_NODE, graphManager))
                    .as("Dajuesi southbound entrance corridor participates as a candidate")
                    .isNotNull();
            assertThat(context.boundary().locate(HUAWEI_BEIJING_RESEARCH_INSTITUTE))
                    .isEqualTo(SixthRingBoundary.Location.INSIDE);
            assertThat(context.boundary().locate(LONGYIYUAN_WUQING))
                    .isEqualTo(SixthRingBoundary.Location.OUTSIDE);

            RoutingSnapshot snapshot = new RoutingSnapshotBuilder(
                    appProperties,
                    graphManager,
                    new CameraJsonLoader(objectMapper, new CoordinateConverter()),
                    new BlockedEdgeGenerator(),
                    sixthRingManager,
                    sixthRingProperties).build(configuration.cameraJson());
            SixthRingRoutePlanner planner = new SixthRingRoutePlanner(
                    graphManager,
                    sixthRingManager,
                    new GraphHopperRoutingEngine(graphManager, appProperties),
                    sixthRingProperties);
            RouteSafetyValidator validator = new RouteSafetyValidator();

            PlannedRoute outbound = planner.plan(
                    HUAWEI_BEIJING_RESEARCH_INSTITUTE,
                    LONGYIYUAN_WUQING,
                    snapshot);
            assertThat(outbound.planningMode())
                    .isEqualTo(RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND);
            assertThat(outbound.boundaryCrossing()).isNotNull();
            assertThat(validator.validate(outbound, snapshot).conflictCount()).isZero();
            List<RouteTraceSupport.EdgeRun> outboundRuns =
                    RouteTraceSupport.edgeRuns(outbound.trace());
            TollCorridorTopology.TollCorridor usedEntry =
                    usedCorridor(outboundRuns, topology, ENTRY);
            assertThat(usedEntry)
                    .as("outbound route enters the sixth ring via a verified entry corridor")
                    .isNotNull();
            assertThat(isSouthboundMainline(usedEntry, graphManager))
                    .as("outbound entry corridor leads onto the southbound mainline")
                    .isTrue();
            System.out.println("HUAWEI_OUTBOUND_USED_ENTRY_TOLL="
                    + usedEntry.tollNodeId()
                    + " complex=" + usedEntry.complexId());

            PlannedRoute inbound = planner.plan(
                    LONGYIYUAN_WUQING,
                    HUAWEI_BEIJING_RESEARCH_INSTITUTE,
                    snapshot);
            assertThat(inbound.planningMode())
                    .isEqualTo(RoutePlanningMode.CROSS_BOUNDARY_INBOUND);
            assertThat(inbound.boundaryCrossing()).isNotNull();
            assertThat(validator.validate(inbound, snapshot).conflictCount()).isZero();
            assertThat(usesAnyCorridor(
                    RouteTraceSupport.edgeRuns(inbound.trace()), topology))
                    .as("inbound Longyiyuan route uses a verified sixth-ring corridor")
                    .isTrue();

            writeAuditIfConfigured(topology);
        } finally {
            graphManager.close();
        }
    }

    @Test
    void identifiesRhtInterchangeCorridorsAndRoutesZhuxinzhuangToLongyiyuan() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real.pbf, real.graph.cache, real.camera.json and "
                        + "sixth.ring.boundary.input to run the real interchange corridor test");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AppProperties appProperties = appProperties(configuration);
        SixthRingProperties sixthRingProperties = new SixthRingProperties(
                configuration.boundary().toString(),
                false,
                50,
                1000,
                100,
                100,
                2_000_000,
                Duration.ofSeconds(5),
                false,
                4);
        GraphHopperManager graphManager = new GraphHopperManager(appProperties);
        graphManager.initialize();
        try {
            SixthRingRoutingManager sixthRingManager = new SixthRingRoutingManager(
                    graphManager,
                    sixthRingProperties,
                    new SixthRingBoundaryLoader(objectMapper));
            sixthRingManager.initialize();
            SixthRingRoutingContext context = sixthRingManager.requireContext();
            HighwayInterchangeTopology interchangeTopology = context.interchangeTopology();

            List<HighwayInterchangeTopology.InterchangeCorridor> outboundCorridors =
                    interchangeTopology.corridors().stream()
                            .filter(corridor -> corridor.role()
                                    == HighwayInterchangeTopology.Role.R_TO_HT)
                            .filter(corridor -> corridorNearXuzhuangqiao(
                                    corridor, graphManager))
                            .toList();
            List<HighwayInterchangeTopology.InterchangeCorridor> inboundCorridors =
                    interchangeTopology.corridors().stream()
                            .filter(corridor -> corridor.role()
                                    == HighwayInterchangeTopology.Role.HT_TO_R)
                            .filter(corridor -> corridorNearXuzhuangqiao(
                                    corridor, graphManager))
                            .toList();
            assertThat(outboundCorridors)
                    .as("Xuzhuangqiao R -> H-T interchange corridor")
                    .isNotEmpty();
            assertThat(inboundCorridors)
                    .as("Xuzhuangqiao H-T -> R interchange corridor")
                    .isNotEmpty();
            System.out.println("XUZHUANGQIAO_R_TO_HT_CORRIDORS=" + outboundCorridors.size());
            System.out.println("XUZHUANGQIAO_HT_TO_R_CORRIDORS=" + inboundCorridors.size());
            System.out.println("INTERCHANGE_AUDIT=" + interchangeTopology.audit());

            RoutingSnapshot snapshot = new RoutingSnapshotBuilder(
                    appProperties,
                    graphManager,
                    new CameraJsonLoader(objectMapper, new CoordinateConverter()),
                    new BlockedEdgeGenerator(),
                    sixthRingManager,
                    sixthRingProperties).build(configuration.cameraJson());
            SixthRingRoutePlanner planner = new SixthRingRoutePlanner(
                    graphManager,
                    sixthRingManager,
                    new GraphHopperRoutingEngine(graphManager, appProperties),
                    sixthRingProperties);
            RouteSafetyValidator validator = new RouteSafetyValidator();

            PlannedRoute outbound = planner.plan(
                    ZHUXINZHUANG_NANQU,
                    LONGYIYUAN_WUQING,
                    snapshot);
            assertThat(outbound.planningMode())
                    .isEqualTo(RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND);
            assertThat(validator.validate(outbound, snapshot).conflictCount()).isZero();
            List<RouteTraceSupport.EdgeRun> outboundRuns =
                    RouteTraceSupport.edgeRuns(outbound.trace());
            System.out.println("OUTBOUND_NEAR_XUZHUANGQIAO=" + passesNearXuzhuangqiao(
                    outbound.referenceSegment().geometry()));
            assertThat(usesInterchangeCorridor(outboundRuns, interchangeTopology))
                    .as("outbound Zhuxinzhuang route uses a verified R-H-T interchange corridor")
                    .isTrue();
            assertThat(outbound.distanceMeters())
                    .as("outbound route returns to the direct Xuzhuangqiao expressway path")
                    .isBetween(143_000.0, 156_000.0);

            PlannedRoute inbound = planner.plan(
                    LONGYIYUAN_WUQING,
                    ZHUXINZHUANG_NANQU,
                    snapshot);
            assertThat(inbound.planningMode())
                    .isEqualTo(RoutePlanningMode.CROSS_BOUNDARY_INBOUND);
            assertThat(validator.validate(inbound, snapshot).conflictCount()).isZero();
            List<RouteTraceSupport.EdgeRun> inboundRuns =
                    RouteTraceSupport.edgeRuns(inbound.trace());
            assertThat(usesInterchangeCorridor(inboundRuns, interchangeTopology))
                    .as("inbound Longyiyuan route uses a verified R-H-T interchange corridor")
                    .isTrue();
            assertThat(inbound.distanceMeters())
                    .as("inbound route returns to the direct Xuzhuangqiao expressway path")
                    .isBetween(143_000.0, 156_000.0);
            System.out.println("ZHUXINZHUANG_OUTBOUND_DISTANCE=" + outbound.distanceMeters());
            System.out.println("LONGYIYUAN_INBOUND_DISTANCE=" + inbound.distanceMeters());
        } finally {
            graphManager.close();
        }
    }

    private static boolean corridorNearXuzhuangqiao(
            HighwayInterchangeTopology.InterchangeCorridor corridor,
            GraphHopperManager graphManager) {
        return corridor.directedEdgeKeys().stream().anyMatch(edgeKey -> {
            var edge = graphManager.requireHopper().getBaseGraph()
                    .getEdgeIteratorStateForKey(edgeKey);
            var points = edge.fetchWayGeometry(com.graphhopper.util.FetchMode.ALL);
            for (int index = 0; index < points.size(); index++) {
                if (points.getLon(index) > 116.60 && points.getLon(index) < 116.70
                        && points.getLat(index) > 39.75 && points.getLat(index) < 39.85) {
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean passesNearXuzhuangqiao(List<Wgs84Coordinate> geometry) {
        return geometry.stream().anyMatch(point ->
                point.lng() > 116.60 && point.lng() < 116.70
                        && point.lat() > 39.75 && point.lat() < 39.85);
    }

    private static boolean usesInterchangeCorridor(
            List<RouteTraceSupport.EdgeRun> runs,
            HighwayInterchangeTopology topology) {
        return topology.corridors().stream()
                .anyMatch(corridor -> containsFullCorridor(runs, corridor));
    }

    private static boolean containsFullCorridor(
            List<RouteTraceSupport.EdgeRun> runs,
            HighwayInterchangeTopology.InterchangeCorridor corridor) {
        return matchedCorridorStart(runs, corridor) >= 0;
    }

    private static int matchedCorridorStart(
            List<RouteTraceSupport.EdgeRun> runs,
            HighwayInterchangeTopology.InterchangeCorridor corridor) {
        List<Integer> keys = corridor.directedEdgeKeys();
        for (int start = 0; start + keys.size() <= runs.size(); start++) {
            boolean match = true;
            for (int offset = 0; offset < keys.size(); offset++) {
                if (runs.get(start + offset).originalEdgeKey() != keys.get(offset)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return start;
            }
        }
        return -1;
    }

    private static boolean hasCorridor(
            TollCorridorTopology topology,
            long tollNodeId,
            TollCorridorTopology.Role role) {
        return topology.corridors().stream()
                .anyMatch(corridor -> corridor.role() == role
                        && corridor.tollNodeId() == tollNodeId);
    }

    private static TollCorridorTopology.TollCorridor southboundEntryCorridor(
            TollCorridorTopology topology,
            long tollNodeId,
            GraphHopperManager graphManager) {
        return topology.corridors().stream()
                .filter(corridor -> corridor.role() == ENTRY
                        && corridor.tollNodeId() == tollNodeId
                        && isSouthboundMainline(corridor, graphManager))
                .findFirst()
                .orElse(null);
    }

    private static TollCorridorTopology.TollCorridor usedCorridor(
            List<RouteTraceSupport.EdgeRun> runs,
            TollCorridorTopology topology,
            TollCorridorTopology.Role role) {
        return topology.corridors().stream()
                .filter(corridor -> corridor.role() == role
                        && containsFullCorridor(runs, corridor))
                .findFirst()
                .orElse(null);
    }

    private static boolean isSouthboundMainline(
            TollCorridorTopology.TollCorridor corridor,
            GraphHopperManager graphManager) {
        EdgeIteratorState mainline = graphManager.requireHopper().getBaseGraph()
                .getEdgeIteratorStateForKey(corridor.sixthRingMainlineEdgeKey());
        NodeAccess nodes = graphManager.requireHopper().getBaseGraph().getNodeAccess();
        return nodes.getLat(mainline.getAdjNode())
                < nodes.getLat(mainline.getBaseNode());
    }

    private static boolean containsFullCorridor(
            List<RouteTraceSupport.EdgeRun> runs,
            TollCorridorTopology.TollCorridor corridor) {
        List<Integer> keys = corridor.directedEdgeKeys();
        for (int start = 0; start + keys.size() <= runs.size(); start++) {
            boolean match = true;
            for (int offset = 0; offset < keys.size(); offset++) {
                if (runs.get(start + offset).originalEdgeKey() != keys.get(offset)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesAnyCorridor(
            List<RouteTraceSupport.EdgeRun> runs,
            TollCorridorTopology topology) {
        return topology.corridors().stream()
                .anyMatch(corridor -> containsFullCorridor(runs, corridor));
    }

    private static void writeAuditIfConfigured(TollCorridorTopology topology) throws Exception {
        String configuredOutput = System.getProperty("toll.corridor.audit.output");
        if (configuredOutput == null || configuredOutput.isBlank()) {
            return;
        }
        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();
        TollCorridorTopology.Audit audit = topology.audit();
        StringBuilder report = new StringBuilder("# 六环收费站走廊审计报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append("\n")
                .append("- 数据来源：京津冀 PBF 全量 `barrier=toll_booth` 节点\n")
                .append("- 判定规则：仅保留有向拓扑连通六环主路 `R` 的入口/出口走廊；")
                .append("同一收费节点若存在多个主路汇入方向，分别生成走廊\n\n")
                .append("| 指标 | 数量 |\n|---|---:|\n")
                .append("| PBF 收费节点 | ").append(audit.sourceTollBooths()).append(" |\n")
                .append("| 已映射图节点 | ").append(audit.mappedTollBooths()).append(" |\n")
                .append("| 六环关联 | ").append(audit.sixthRingRelatedTollBooths()).append(" |\n")
                .append("| 入口走廊 | ").append(audit.entryCorridors()).append(" |\n")
                .append("| 出口走廊 | ").append(audit.exitCorridors()).append(" |\n")
                .append("| 未确认关联节点 | ").append(audit.unresolvedRelatedTollBooths()).append(" |\n\n")
                .append("## 走廊明细\n\n")
                .append("| 复合体 | 角色 | 收费节点 | 名称 | 距离米 | 有向边数 | 主路边 |\n")
                .append("|---|---|---:|---|---:|---:|---:|\n");
        topology.corridors().stream()
                .sorted(java.util.Comparator
                        .comparing(TollCorridorTopology.TollCorridor::complexId)
                        .thenComparing(TollCorridorTopology.TollCorridor::role)
                        .thenComparing(TollCorridorTopology.TollCorridor::tollNodeId))
                .forEach(corridor -> report.append("| ")
                        .append(corridor.complexId()).append(" | ")
                        .append(corridor.role()).append(" | ")
                        .append(corridor.tollNodeId()).append(" | ")
                        .append(corridor.name() == null ? "" : corridor.name()).append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", corridor.distanceMeters()))
                        .append(" | ")
                        .append(corridor.directedEdgeKeys().size()).append(" | ")
                        .append(corridor.sixthRingMainlineEdgeKey()).append(" |\n"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println("TOLL_CORRIDOR_AUDIT=" + output);
    }

    private static Configuration configuration() {
        String pbf = System.getProperty("real.pbf");
        String graphCache = System.getProperty("real.graph.cache");
        String cameraJson = System.getProperty("real.camera.json");
        String boundary = System.getProperty("sixth.ring.boundary.input");
        if (pbf == null || pbf.isBlank()
                || graphCache == null || graphCache.isBlank()
                || cameraJson == null || cameraJson.isBlank()
                || boundary == null || boundary.isBlank()) {
            return null;
        }
        return new Configuration(
                Path.of(pbf).toAbsolutePath().normalize(),
                Path.of(graphCache).toAbsolutePath().normalize(),
                Path.of(cameraJson).toAbsolutePath().normalize(),
                Path.of(boundary).toAbsolutePath().normalize());
    }

    private static AppProperties appProperties(Configuration configuration) {
        Path currentCache = configuration.graphCache().resolveSibling(
                configuration.graphCache().getFileName() + "-current-reference");
        return new AppProperties(
                new AppProperties.Routing(
                        configuration.pbf().toString(),
                        currentCache.toString(),
                        configuration.graphCache().toString(),
                        RoutingProfileMode.COMPLIANT_TIME_V2,
                        50,
                        2,
                        4,
                        Duration.ofSeconds(30),
                        2_000_000),
                new AppProperties.Cameras(
                        configuration.cameraJson().toString(),
                        configuration.graphCache().resolve(
                                "toll-corridor-test-snapshots").toString(),
                        true,
                        10_000,
                        updateProperties()),
                new AppProperties.Admin(true));
    }

    private record Configuration(
            Path pbf,
            Path graphCache,
            Path cameraJson,
            Path boundary) {
    }
}
