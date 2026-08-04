package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.PMap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.Completion.MAX_VISITED_STATES;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.Completion.TIMEOUT;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.SearchDirection.FORWARD;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.SearchDirection.REVERSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SixthRingMultiTargetSearchPocTest {
    private static final Wgs84Coordinate INSIDE_CONTROL_POINT =
            new Wgs84Coordinate(116.3970, 39.9080);
    private static final Wgs84Coordinate OUTSIDE_REFERENCE_POINT =
            new Wgs84Coordinate(117.200983, 39.084158);

    @Test
    void measuresForwardAndReverseMultiTargetSearchOnTheCurrentGraph() throws Exception {
        String configuredPbf = System.getProperty("real.pbf");
        String configuredGraphCache = System.getProperty("real.graph.cache");
        String configuredCameraJson = System.getProperty("real.camera.json");
        String configuredCandidates = System.getProperty("sixth.ring.directed.input");
        String configuredReport = System.getProperty("sixth.ring.search.report.output");
        assumeTrue(allConfigured(
                        configuredPbf,
                        configuredGraphCache,
                        configuredCameraJson,
                        configuredCandidates,
                        configuredReport),
                "Set real graph, camera, directed candidates and report properties to run this POC");

        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        Path graphCache = Path.of(configuredGraphCache).toAbsolutePath().normalize();
        Path cameraJson = Path.of(configuredCameraJson).toAbsolutePath().normalize();
        Path candidateInput = Path.of(configuredCandidates).toAbsolutePath().normalize();
        Path reportOutput = Path.of(configuredReport).toAbsolutePath().normalize();
        AppProperties properties = properties(pbf, graphCache, cameraJson);
        GraphHopperManager manager = new GraphHopperManager(properties);
        manager.initialize();
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            RoutingSnapshot snapshot = new RoutingSnapshotBuilder(
                    properties,
                    manager,
                    new CameraJsonLoader(objectMapper, new CoordinateConverter()),
                    new BlockedEdgeGenerator()).build(cameraJson);
            CandidateInput input = readCandidates(
                    objectMapper, candidateInput, manager.requireGraphFingerprint());
            HardAvoidingGraphHopper hopper = manager.requireHopper();
            BaseGraph graph = hopper.getBaseGraph();
            BooleanEncodedValue carAccess = hopper.getEncodingManager()
                    .getBooleanEncodedValue("car_access");
            EdgeFilter snapFilter = edge -> edge.get(carAccess) || edge.getReverse(carAccess);
            Snap snap = hopper.getLocationIndex().findClosest(
                    INSIDE_CONTROL_POINT.lat(), INSIDE_CONTROL_POINT.lng(), snapFilter);
            assertThat(snap.isValid()).isTrue();

            Weighting profileWeighting = hopper.createWeighting(
                    hopper.getProfile("car"), new PMap());
            assertThat(profileWeighting.hasTurnCosts()).isTrue();
            SearchMeasurement outbound = search(
                    graph,
                    new DistanceFirstLegalityWeighting(profileWeighting),
                    snapshot,
                    snap.getClosestNode(),
                    input.outbound(),
                    FORWARD);
            SearchMeasurement inbound = search(
                    graph,
                    new DistanceFirstLegalityWeighting(profileWeighting),
                    snapshot,
                    snap.getClosestNode(),
                    input.inbound(),
                    REVERSE);

            assertCompleted(outbound.result());
            assertCompleted(inbound.result());
            assertThat(outbound.result().candidates()).isNotEmpty();
            assertThat(inbound.result().candidates()).isNotEmpty();
            assertSafe(graph, outbound.result(), snapshot);
            assertSafe(graph, inbound.result(), snapshot);
            GraphHopperRoutingEngine routingEngine = new GraphHopperRoutingEngine(manager, properties);
            List<ReferenceOption> outboundReferences = referenceOptions(
                    "OUTBOUND",
                    input.outboundById(),
                    outbound.result(),
                    routingEngine,
                    snapshot);
            List<ReferenceOption> inboundReferences = referenceOptions(
                    "INBOUND",
                    input.inboundById(),
                    inbound.result(),
                    routingEngine,
                    snapshot);
            assertThat(outboundReferences).isNotEmpty();
            assertThat(inboundReferences).isNotEmpty();
            writeReport(
                    reportOutput,
                    candidateInput,
                    manager.requireGraphFingerprint(),
                    snapshot,
                    snap,
                    input,
                    outbound,
                    inbound,
                    outboundReferences,
                    inboundReferences);
        } finally {
            manager.close();
        }
    }

    private static SearchMeasurement search(
            BaseGraph graph,
            Weighting baseWeighting,
            RoutingSnapshot snapshot,
            int sourceNode,
            List<Candidate> candidates,
            EdgeKeyMultiTargetDijkstraPoc.SearchDirection direction) {
        SearchAudit audit = new SearchAudit();
        Weighting weighting = new BlockedEdgeWeighting(
                baseWeighting,
                snapshot.blockedEdges(),
                audit,
                graph.getEdges());
        List<EdgeKeyMultiTargetDijkstraPoc.Portal> portals = candidates.stream()
                .map(Candidate::portal)
                .toList();
        long started = System.nanoTime();
        EdgeKeyMultiTargetDijkstraPoc.SearchResult result = EdgeKeyMultiTargetDijkstraPoc.search(
                graph,
                weighting,
                sourceNode,
                portals,
                direction,
                1_000,
                2_000_000,
                Duration.ofSeconds(30));
        return new SearchMeasurement(
                result,
                Duration.ofNanos(System.nanoTime() - started),
                audit);
    }

    private static void assertCompleted(EdgeKeyMultiTargetDijkstraPoc.SearchResult result) {
        assertThat(result.completion()).isNotIn(TIMEOUT, MAX_VISITED_STATES);
        assertThat(result.provenNoRoute()).isFalse();
    }

    private static void assertSafe(
            BaseGraph graph,
            EdgeKeyMultiTargetDijkstraPoc.SearchResult result,
            RoutingSnapshot snapshot) {
        RouteSafetyValidator validator = new RouteSafetyValidator();
        for (EdgeKeyMultiTargetDijkstraPoc.PortalPath path : result.candidates().values()) {
            assertThat(path.edgeKeys())
                    .noneMatch(snapshot.blockedEdges()::isBlockedEdgeKey);
            List<Wgs84Coordinate> geometry = geometry(graph, path.edgeKeys());
            if (geometry.size() >= 2) {
                assertThat(validator.validate(geometry, snapshot).conflicts())
                        .as("JTS camera conflicts for portal %s", path.portal().id())
                        .isEmpty();
            }
        }
    }

    private static List<ReferenceOption> referenceOptions(
            String direction,
            Map<String, Candidate> metadata,
            EdgeKeyMultiTargetDijkstraPoc.SearchResult insideResult,
            GraphHopperRoutingEngine routingEngine,
            RoutingSnapshot snapshot) {
        RouteSafetyValidator validator = new RouteSafetyValidator();
        List<ReferenceOption> options = new ArrayList<>();
        for (EdgeKeyMultiTargetDijkstraPoc.PortalPath insidePath
                : insideResult.candidates().values()) {
            Candidate candidate = metadata.get(insidePath.portal().id());
            Wgs84Coordinate crossing = new Wgs84Coordinate(candidate.lng(), candidate.lat());
            EngineRoute outsideSegment;
            try {
                outsideSegment = "OUTBOUND".equals(direction)
                        ? routingEngine.route(crossing, OUTSIDE_REFERENCE_POINT, snapshot)
                        : routingEngine.route(OUTSIDE_REFERENCE_POINT, crossing, snapshot);
            } catch (RoutingEngineException exception) {
                continue;
            }
            assertThat(validator.validate(outsideSegment.geometry(), snapshot).conflicts())
                    .as("reference segment camera conflicts for portal %s", candidate.id())
                    .isEmpty();
            options.add(new ReferenceOption(
                    direction,
                    candidate.id(),
                    insidePath.distanceMeters(),
                    outsideSegment.distanceMeters(),
                    insidePath.distanceMeters() + outsideSegment.distanceMeters(),
                    outsideSegment.durationMillis()));
        }
        return options.stream()
                .sorted(Comparator.comparingDouble(ReferenceOption::totalDistanceMeters)
                        .thenComparingLong(ReferenceOption::outsideDurationMillis)
                        .thenComparing(ReferenceOption::candidateId))
                .toList();
    }

    private static List<Wgs84Coordinate> geometry(BaseGraph graph, List<Integer> edgeKeys) {
        List<Wgs84Coordinate> result = new ArrayList<>();
        for (int edgeKey : edgeKeys) {
            EdgeIteratorState edge = graph.getEdgeIteratorStateForKey(edgeKey);
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            for (int index = 0; index < points.size(); index++) {
                Wgs84Coordinate point = new Wgs84Coordinate(points.getLon(index), points.getLat(index));
                if (result.isEmpty() || !result.getLast().equals(point)) {
                    result.add(point);
                }
            }
        }
        return List.copyOf(result);
    }

    private static CandidateInput readCandidates(
            ObjectMapper objectMapper,
            Path input,
            String expectedFingerprint) throws Exception {
        JsonNode root = objectMapper.readTree(input.toFile());
        assertThat(root.path("graphFingerprint").asText()).isEqualTo(expectedFingerprint);
        Map<String, Candidate> outbound = new LinkedHashMap<>();
        Map<String, Candidate> inbound = new LinkedHashMap<>();
        for (JsonNode feature : root.path("features")) {
            JsonNode properties = feature.path("properties");
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            Candidate candidate = new Candidate(
                    properties.path("candidateId").asText(),
                    properties.path("direction").asText(),
                    properties.path("candidateType").asText(),
                    properties.path("roadName").asText(),
                    coordinates.get(0).asDouble(),
                    coordinates.get(1).asDouble(),
                    new EdgeKeyMultiTargetDijkstraPoc.Portal(
                            properties.path("candidateId").asText(),
                            properties.path("edgeKey").asInt(),
                            properties.path("fractionFromBase").asDouble()));
            Map<String, Candidate> target = "OUTBOUND".equals(candidate.direction())
                    ? outbound : inbound;
            target.put(candidate.id(), candidate);
        }
        return new CandidateInput(Map.copyOf(outbound), Map.copyOf(inbound));
    }

    private static void writeReport(
            Path output,
            Path candidateInput,
            String graphFingerprint,
            RoutingSnapshot snapshot,
            Snap snap,
            CandidateInput input,
            SearchMeasurement outbound,
            SearchMeasurement inbound,
            List<ReferenceOption> outboundReferences,
            List<ReferenceOption> inboundReferences) throws Exception {
        StringBuilder report = new StringBuilder("# 六环多目标搜索真实图 POC 报告\n\n")
                .append("- 图指纹：`").append(graphFingerprint).append("`\n")
                .append("- 候选输入：`").append(candidateInput).append("`\n")
                .append("- 摄像头快照：`").append(snapshot.cameraSnapshot().version()).append("`\n")
                .append("- 禁行边版本：`").append(snapshot.blockedEdges().blockedEdgeVersion()).append("`\n")
                .append("- 禁行基础边：").append(snapshot.blockedEdges().blockedEdgeCount()).append("\n")
                .append("- 环内控制点：")
                .append(String.format(Locale.ROOT, "%.7f, %.7f", INSIDE_CONTROL_POINT.lng(), INSIDE_CONTROL_POINT.lat()))
                .append("\n")
                .append("- 环外参考点：")
                .append(String.format(Locale.ROOT, "%.7f, %.7f", OUTSIDE_REFERENCE_POINT.lng(), OUTSIDE_REFERENCE_POINT.lat()))
                .append("\n")
                .append("- 吸附节点：").append(snap.getClosestNode())
                .append("，吸附距离：").append(String.format(Locale.ROOT, "%.1f m", snap.getQueryDistance()))
                .append("\n\n")
                .append("## 搜索结果\n\n")
                .append("| 方向 | 原始候选 | Dmin + 1km 候选 | Dmin | 访问状态 | 耗时 | 完成状态 | 禁边拒绝 |\n")
                .append("|---|---:|---:|---:|---:|---:|---|---:|\n");
        appendMeasurement(report, "OUTBOUND", input.outbound().size(), outbound);
        appendMeasurement(report, "INBOUND", input.inbound().size(), inbound);
        report.append("\n当前候选图已启用 turn costs。多目标搜索沿用候选 Profile 的可达性、OSM 禁转和 road_access 入口规则，同时按真实道路米数累计 Dmin，确保 1 公里容差保持业务单位。\n\n")
                .append("## 入选候选\n\n")
                .append("| 方向 | ID | 距离 | 类型 | WGS84 | 道路名 |\n")
                .append("|---|---|---:|---|---|---|\n");
        appendCandidates(report, "OUTBOUND", input.outboundById(), outbound.result());
        appendCandidates(report, "INBOUND", input.inboundById(), inbound.result());
        report.append("\n## 完整参考路线比较\n\n")
                .append("| 方向 | 候选 ID | 环内距离 | 环外参考段 | 完整参考距离 | 结果 |\n")
                .append("|---|---|---:|---:|---:|---|\n");
        appendReferenceOptions(report, outboundReferences);
        appendReferenceOptions(report, inboundReferences);
        report.append("\n每个方向按完整参考距离选择表中第一名。环外参考段使用相同的候选 `car` Profile；结果仍需扩展到东南西北多组控制点并完成人工地图抽查。\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private static void appendMeasurement(
            StringBuilder report,
            String direction,
            int sourceCandidates,
            SearchMeasurement measurement) {
        EdgeKeyMultiTargetDijkstraPoc.SearchResult result = measurement.result();
        report.append("| ").append(direction).append(" | ")
                .append(sourceCandidates).append(" | ")
                .append(result.candidates().size()).append(" | ")
                .append(String.format(Locale.ROOT, "%.0f m", result.minimumDistanceMeters())).append(" | ")
                .append(result.visitedStates()).append(" | ")
                .append(String.format(Locale.ROOT, "%.1f ms", measurement.elapsed().toNanos() / 1_000_000.0))
                .append(" | ").append(result.completion()).append(" | ")
                .append(measurement.audit().blockedRejections()).append(" |\n");
    }

    private static void appendCandidates(
            StringBuilder report,
            String direction,
            Map<String, Candidate> metadata,
            EdgeKeyMultiTargetDijkstraPoc.SearchResult result) {
        result.candidates().values().stream()
                .sorted(Comparator.comparingDouble(EdgeKeyMultiTargetDijkstraPoc.PortalPath::distanceMeters))
                .forEach(path -> {
                    Candidate candidate = metadata.get(path.portal().id());
                    report.append("| ").append(direction).append(" | ")
                            .append(candidate.id()).append(" | ")
                            .append(String.format(Locale.ROOT, "%.0f m", path.distanceMeters())).append(" | ")
                            .append(candidate.candidateType()).append(" | ")
                            .append(String.format(Locale.ROOT, "%.7f, %.7f", candidate.lng(), candidate.lat()))
                            .append(" | ").append(candidate.roadName().replace("|", "\\|"))
                            .append(" |\n");
                });
    }

    private static void appendReferenceOptions(
            StringBuilder report,
            List<ReferenceOption> options) {
        for (int index = 0; index < options.size(); index++) {
            ReferenceOption option = options.get(index);
            report.append("| ").append(option.direction()).append(" | ")
                    .append(option.candidateId()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.0f m", option.insideDistanceMeters())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.0f m", option.outsideDistanceMeters())).append(" | ")
                    .append(String.format(Locale.ROOT, "%.0f m", option.totalDistanceMeters())).append(" | ")
                    .append(index == 0 ? "SELECTED" : "candidate")
                    .append(" |\n");
        }
    }

    private static boolean allConfigured(String... values) {
        return java.util.Arrays.stream(values).allMatch(value -> value != null && !value.isBlank());
    }

    private static AppProperties properties(Path pbf, Path graphCache, Path cameraJson) {
        RoutingProfileMode profileMode = RoutingProfileMode.valueOf(
                System.getProperty("real.routing.profile.mode", RoutingProfileMode.CURRENT.name()));
        Path currentCache = profileMode == RoutingProfileMode.CURRENT
                ? graphCache
                : graphCache.resolveSibling(graphCache.getFileName() + "-current-reference");
        Path candidateCache = profileMode == RoutingProfileMode.COMPLIANT_DISTANCE_V1
                ? graphCache
                : graphCache.resolveSibling(graphCache.getFileName() + "-candidate-reference");
        return new AppProperties(
                new AppProperties.Routing(
                        pbf.toString(), currentCache.toString(), candidateCache.toString(), profileMode,
                        30, 2, 4,
                        Duration.ofSeconds(30), 2_000_000),
                new AppProperties.Cameras(
                        cameraJson.toString(), graphCache.resolve("poc-snapshots").toString(),
                        true, 10_000, 1, updateProperties()),
                new AppProperties.Admin(true));
    }

    private record Candidate(
            String id,
            String direction,
            String candidateType,
            String roadName,
            double lng,
            double lat,
            EdgeKeyMultiTargetDijkstraPoc.Portal portal) {
    }

    private record CandidateInput(
            Map<String, Candidate> outboundById,
            Map<String, Candidate> inboundById) {
        List<Candidate> outbound() {
            return outboundById.values().stream().toList();
        }

        List<Candidate> inbound() {
            return inboundById.values().stream().toList();
        }
    }

    private record SearchMeasurement(
            EdgeKeyMultiTargetDijkstraPoc.SearchResult result,
            Duration elapsed,
            SearchAudit audit) {
    }

    private record ReferenceOption(
            String direction,
            String candidateId,
            double insideDistanceMeters,
            double outsideDistanceMeters,
            double totalDistanceMeters,
            long outsideDurationMillis) {
    }

    private record DistanceFirstLegalityWeighting(Weighting delegate) implements Weighting {

        @Override
        public double calcMinWeightPerDistance() {
            return 1;
        }

        @Override
        public double calcEdgeWeight(EdgeIteratorState edgeState, boolean reverse) {
            return Double.isFinite(delegate.calcEdgeWeight(edgeState, reverse))
                    ? edgeState.getDistance()
                    : Double.POSITIVE_INFINITY;
        }

        @Override
        public long calcEdgeMillis(EdgeIteratorState edgeState, boolean reverse) {
            return delegate.calcEdgeMillis(edgeState, reverse);
        }

        @Override
        public double calcTurnWeight(int inEdge, int viaNode, int outEdge) {
            return delegate.calcTurnWeight(inEdge, viaNode, outEdge);
        }

        @Override
        public long calcTurnMillis(int inEdge, int viaNode, int outEdge) {
            return delegate.calcTurnMillis(inEdge, viaNode, outEdge);
        }

        @Override
        public boolean hasTurnCosts() {
            return delegate.hasTurnCosts();
        }

        @Override
        public String getName() {
            return "poc-distance-first|" + delegate.getName();
        }
    }
}
