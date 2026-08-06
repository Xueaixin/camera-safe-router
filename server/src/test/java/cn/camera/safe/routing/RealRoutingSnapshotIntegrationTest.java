package cn.camera.safe.routing;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.CoordinateSource;
import cn.camera.safe.api.model.InputCoordinate;
import cn.camera.safe.api.model.RouteRequest;
import cn.camera.safe.api.model.VehicleType;
import cn.camera.safe.application.RoutePlanningService;
import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.camera.CameraLoadResult;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import cn.camera.safe.validation.SafetyValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealRoutingSnapshotIntegrationTest {
    private static final String FIXTURE_RESOURCE = "/fixtures/jingjinji-route-regression.json";

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesCurrentSnapshotRoutesErrorsAndCacheLifecycle() throws Exception {
        String configuredPbf = System.getProperty("real.pbf");
        String configuredCameras = System.getProperty("real.camera.json");
        assumeTrue(configuredPbf != null && !configuredPbf.isBlank()
                        && configuredCameras != null && !configuredCameras.isBlank(),
                "Set -Dreal.pbf and -Dreal.camera.json to run the real snapshot integration test");

        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        Path camerasPath = Path.of(configuredCameras).toAbsolutePath().normalize();
        String configuredGraphCache = System.getProperty("real.graph.cache");
        Path graphCache = configuredGraphCache == null || configuredGraphCache.isBlank()
                ? temporaryDirectory.resolve("graph-cache")
                : Path.of(configuredGraphCache).toAbsolutePath().normalize();
        String initialPhase = configuredGraphCache == null || configuredGraphCache.isBlank()
                ? "冷导图"
                : "已发布缓存";
        Path snapshots = temporaryDirectory.resolve("snapshots");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RouteFixtureSet fixtures;
        try (InputStream stream = RealRoutingSnapshotIntegrationTest.class
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream).isNotNull();
            fixtures = objectMapper.readValue(stream, RouteFixtureSet.class);
        }
        assertThat(Hashing.sha256(pbf)).isEqualTo(fixtures.pbfSha256());
        assertThat(Hashing.sha256(camerasPath)).isEqualTo(fixtures.cameraSha256());

        AppProperties properties = properties(pbf, camerasPath, graphCache, snapshots);
        RoutingSnapshot routingSnapshot;
        String graphFingerprint;
        List<RouteResult> coldResults;
        GraphHopperManager graphManager = new GraphHopperManager(properties);
        graphManager.initialize();
        try {
            routingSnapshot = buildAndVerifySnapshot(
                    objectMapper, camerasPath, properties, graphManager);
            graphFingerprint = graphManager.requireGraphFingerprint();
            coldResults = verifyRoutes(
                    initialPhase, fixtures.routes(), graphManager, properties, routingSnapshot);
            verifyNoCompliantRoutes(
                    fixtures.noCompliantRoutes(), graphManager, properties, routingSnapshot);
            verifyRealBusinessErrors(
                    fixtures.routes().getFirst(),
                    fixtures.noCompliantRoutes().getFirst(),
                    graphManager,
                    properties,
                    routingSnapshot);
        } finally {
            graphManager.close();
        }

        assertThat(Files.isRegularFile(graphCache.resolve("camera-safe-source.sha256"))).isTrue();
        AppProperties cacheOnlyProperties = properties(
                temporaryDirectory.resolve("missing.pbf"), camerasPath, graphCache, snapshots);
        List<RouteResult> warmResults;
        GraphHopperManager cachedManager = new GraphHopperManager(cacheOnlyProperties);
        cachedManager.initialize();
        try {
            assertThat(cachedManager.requireGraphFingerprint()).isEqualTo(graphFingerprint);
            warmResults = verifyRoutes(
                    "缓存重载", fixtures.routes(), cachedManager, cacheOnlyProperties, routingSnapshot);
        } finally {
            cachedManager.close();
        }

        Path mismatchedPbf = temporaryDirectory.resolve("mismatched.osm.pbf");
        Files.writeString(mismatchedPbf, "different pbf hash", StandardCharsets.US_ASCII);
        GraphHopperManager mismatchedManager = new GraphHopperManager(
                properties(mismatchedPbf, camerasPath, graphCache, snapshots));
        assertThatThrownBy(mismatchedManager::initialize)
                .hasRootCauseMessage("配置的 PBF 与现有路网缓存不匹配");

        writeRouteReportIfConfigured(
                pbf,
                camerasPath,
                graphFingerprint,
                coldResults,
                warmResults,
                fixtures.noCompliantRoutes());
    }

    private RoutingSnapshot buildAndVerifySnapshot(
            ObjectMapper objectMapper,
            Path camerasPath,
            AppProperties properties,
            GraphHopperManager graphManager) throws Exception {
        CameraLoadResult loadResult = new CameraJsonLoader(
                objectMapper, new CoordinateConverter()).load(camerasPath);
        assertThat(loadResult.isValid()).isTrue();
        assertThat(loadResult.sourceSha256())
                .isEqualTo("0c16ed44ef2e24cd4759afba28d6448925db699a4d3b38b05980f076e6ac7404");
        CameraSnapshot cameraSnapshot = CameraSnapshot.from(loadResult);
        BlockedEdgeGenerator generator = new BlockedEdgeGenerator();
        BlockedEdgeBuildResult radius20 = generator.generate(
                cameraSnapshot, graphManager.requireRoadEdgeIndex(), 20);
        BlockedEdgeBuildResult radius30 = generator.generate(
                cameraSnapshot, graphManager.requireRoadEdgeIndex(), 30);
        BlockedEdgeBuildResult radius50 = generator.generate(
                cameraSnapshot, graphManager.requireRoadEdgeIndex(), 50);

        int graphNodes = graphManager.requireHopper().getBaseGraph().getNodes();
        int graphEdges = graphManager.requireHopper().getBaseGraph().getEdges();
        int indexedEdges = graphManager.requireRoadEdgeIndex().indexedEdgeCount();
        boolean candidateProfile = properties.routing().profileMode()
                == RoutingProfileMode.COMPLIANT_DISTANCE_V1;
        if (candidateProfile) {
            assertThat(graphManager.requireHopper().getProfile("car").hasTurnCosts()).isTrue();
            assertThat(graphManager.requireHopper().getEncodingManager().getTurnEncodedValues()).isNotEmpty();
            assertThat(graphNodes).isEqualTo(2_360_314);
            assertThat(graphEdges).isEqualTo(3_234_241);
            assertThat(indexedEdges).isEqualTo(2_906_087);
        } else {
            assertThat(graphNodes).isEqualTo(2_360_314);
            assertThat(graphEdges).isEqualTo(3_234_067);
            assertThat(indexedEdges).isEqualTo(2_905_913);
        }
        assertThat(cameraSnapshot.sourceRecordCount()).isEqualTo(6_803);
        assertThat(cameraSnapshot.retainedRecordCount()).isEqualTo(5_707);
        assertThat(cameraSnapshot.outsideSixRingRecordCount()).isEqualTo(1_096);
        assertThat(cameraSnapshot.unrecognizedSixRingOutRecordCount()).isEqualTo(7);
        if (candidateProfile) {
            System.out.printf(
                    "CANDIDATE_BASELINE nodes=%d edges=%d indexedEdges=%d "
                            + "radius20=%d/%d/%d radius30=%d/%d/%d radius50=%d/%d/%d%n",
                    graphNodes,
                    graphEdges,
                    indexedEdges,
                    radius20.matchedCameraCount(),
                    radius20.unmatchedCameraIds().size(),
                    radius20.snapshot().blockedEdgeCount(),
                    radius30.matchedCameraCount(),
                    radius30.unmatchedCameraIds().size(),
                    radius30.snapshot().blockedEdgeCount(),
                    radius50.matchedCameraCount(),
                    radius50.unmatchedCameraIds().size(),
                    radius50.snapshot().blockedEdgeCount());
            assertThat(radius20.matchedCameraCount()).isEqualTo(5_678);
            assertThat(radius20.unmatchedCameraIds()).hasSize(29);
            assertThat(radius20.snapshot().blockedEdgeCount()).isEqualTo(14_639);
            assertThat(radius30.matchedCameraCount()).isEqualTo(5_688);
            assertThat(radius30.unmatchedCameraIds()).hasSize(19);
            assertThat(radius30.snapshot().blockedEdgeCount()).isEqualTo(19_771);
            assertThat(radius50.matchedCameraCount()).isEqualTo(5_692);
            assertThat(radius50.unmatchedCameraIds()).hasSize(15);
            assertThat(radius50.snapshot().blockedEdgeCount()).isEqualTo(27_610);
        } else {
            assertThat(radius20.matchedCameraCount()).isEqualTo(5_678);
            assertThat(radius20.unmatchedCameraIds()).hasSize(29);
            assertThat(radius20.snapshot().blockedEdgeCount()).isEqualTo(14_616);
            assertThat(radius30.matchedCameraCount()).isEqualTo(5_688);
            assertThat(radius30.unmatchedCameraIds()).hasSize(19);
            assertThat(radius30.snapshot().blockedEdgeCount()).isEqualTo(19_731);
            assertThat(radius50.matchedCameraCount()).isEqualTo(5_692);
            assertThat(radius50.unmatchedCameraIds()).hasSize(15);
            assertThat(radius50.snapshot().blockedEdgeCount()).isEqualTo(27_548);
        }
        assertThat(radius30.snapshot().blockedForwardCount())
                .isEqualTo(radius30.snapshot().blockedReverseCount())
                .isPositive();

        RoutingSnapshot routingSnapshot = new RoutingSnapshot(
                cameraSnapshot,
                new CameraSpatialIndex(cameraSnapshot.cameras()),
                radius30.snapshot(),
                graphManager.requireGraphFingerprint(),
                30,
                radius30.matchedCameraCount(),
                radius30.unmatchedCameraIds());
        Path persisted = new RoutingSnapshotStore(objectMapper, properties).persist(routingSnapshot);
        assertThat(persisted).isRegularFile();
        var persistedJson = objectMapper.readTree(persisted.toFile());
        assertThat(persistedJson.path("cameraSourceRecordCount").asInt()).isEqualTo(6_803);
        assertThat(persistedJson.path("cameraRetainedRecordCount").asInt()).isEqualTo(5_707);
        assertThat(persistedJson.path("cameraOutsideSixRingRecordCount").asInt()).isEqualTo(1_096);
        assertThat(persistedJson.path("cameraUnrecognizedSixRingOutRecordCount").asInt()).isEqualTo(7);
        assertThat(persistedJson.path("cameraCount").asInt()).isEqualTo(5_707);
        return routingSnapshot;
    }

    private static List<RouteResult> verifyRoutes(
            String phase,
            List<RouteFixture> fixtures,
            GraphHopperManager graphManager,
            AppProperties properties,
            RoutingSnapshot snapshot) {
        RoutingEngine engine = new GraphHopperRoutingEngine(graphManager, properties);
        RouteSafetyValidator validator = new RouteSafetyValidator();
        List<RouteResult> results = new ArrayList<>();
        for (RouteFixture fixture : fixtures) {
            Wgs84Coordinate start = fixture.start().toCoordinate();
            Wgs84Coordinate end = fixture.end().toCoordinate();
            assertThat(graphManager.contains(start)).as(fixture.id() + " start in bounds").isTrue();
            assertThat(graphManager.contains(end)).as(fixture.id() + " end in bounds").isTrue();
            assertThat(validator.isRestricted(start, snapshot))
                    .as(fixture.id() + " start outside restricted area").isFalse();
            assertThat(validator.isRestricted(end, snapshot))
                    .as(fixture.id() + " end outside restricted area").isFalse();

            System.out.printf("VERIFY_ROUTE phase=%s id=%s%n", phase, fixture.id());
            EngineRoute route;
            try {
                route = engine.route(start, end, snapshot);
            } catch (RoutingEngineException exception) {
                throw new AssertionError(
                        phase + " 路线失败 " + fixture.id() + ": " + exception.getMessage(),
                        exception);
            }
            SafetyValidationResult safety = validator.validate(route.geometry(), snapshot);
            assertThat(safety.conflictCount()).as(fixture.id() + " camera conflicts").isZero();
            assertThat(route.geometry()).as(fixture.id() + " geometry").hasSizeGreaterThan(1);
            assertThat(route.searchEdgeChecks()).as(fixture.id() + " search audit").isPositive();
            assertThat(route.distanceMeters())
                    .as(fixture.id() + " distance")
                    .isBetween(fixture.minDistanceMeters(), fixture.maxDistanceMeters());
            results.add(new RouteResult(
                    phase,
                    fixture.id(),
                    fixture.category(),
                    route.distanceMeters(),
                    route.durationMillis() / 1_000,
                    route.geometry().size(),
                    route.searchEdgeChecks(),
                    route.blockedRejections(),
                    safety.conflictCount()));
        }
        return List.copyOf(results);
    }

    private static void verifyRealBusinessErrors(
            RouteFixture safeFixture,
            RouteFixture noCompliantFixture,
            GraphHopperManager graphManager,
            AppProperties properties,
            RoutingSnapshot snapshot) {
        RoutingSnapshotManager snapshotManager = mock(RoutingSnapshotManager.class);
        when(snapshotManager.current()).thenReturn(Optional.of(snapshot));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RoutingEngine engine = new GraphHopperRoutingEngine(graphManager, properties);
            RoutePlanner planner = (start, end, currentSnapshot) -> {
                EngineRoute route = engine.route(start, end, currentSnapshot);
                RouteLeg safeSegment = new RouteLeg(
                        route.distanceMeters(), route.durationMillis(), route.geometry());
                return new PlannedRoute(
                        RoutePlanningMode.INTERNAL_SAFE,
                        "real-snapshot-test",
                        null,
                        null,
                        null,
                        safeSegment,
                        null,
                        null,
                        route.distanceMeters(),
                        route.durationMillis(),
                        route.geometry(),
                        route.searchEdgeChecks(),
                        route.virtualEdgeChecks(),
                        route.blockedRejections());
            };
            RoutePlanningService service = new RoutePlanningService(
                    graphManager,
                    snapshotManager,
                    new CoordinateConverter(),
                    planner,
                    new RouteSafetyValidator(),
                    executor,
                    properties);
            Wgs84Coordinate safeStart = safeFixture.start().toCoordinate();
            Wgs84Coordinate safeEnd = safeFixture.end().toCoordinate();
            Set<String> unmatchedCameraIds = Set.copyOf(snapshot.unmatchedCameraIds());
            CameraPoint restricted = snapshot.cameraSnapshot().cameras().stream()
                    .filter(camera -> !unmatchedCameraIds.contains(camera.id()))
                    .findFirst()
                    .orElseThrow();

            assertBusinessError(
                    () -> service.plan(request(new Wgs84Coordinate(0, 0), safeEnd)),
                    ErrorCode.OUTSIDE_ROUTING_BOUNDS);
            assertBusinessError(
                    () -> service.plan(request(restricted.wgs84(), safeEnd)),
                    ErrorCode.START_IN_RESTRICTED_AREA);
            assertBusinessError(
                    () -> service.plan(request(safeStart, restricted.wgs84())),
                    ErrorCode.END_IN_RESTRICTED_AREA);
            assertBusinessError(
                    () -> service.plan(request(
                            noCompliantFixture.start().toCoordinate(),
                            noCompliantFixture.end().toCoordinate())),
                    ErrorCode.NO_COMPLIANT_ROUTE);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void verifyNoCompliantRoutes(
            List<RouteFixture> fixtures,
            GraphHopperManager graphManager,
            AppProperties properties,
            RoutingSnapshot restrictedSnapshot) {
        RoutingEngine engine = new GraphHopperRoutingEngine(graphManager, properties);
        RouteSafetyValidator validator = new RouteSafetyValidator();
        RoutingSnapshot unrestrictedSnapshot = new RoutingSnapshot(
                restrictedSnapshot.cameraSnapshot(),
                restrictedSnapshot.cameraIndex(),
                BlockedEdgeSnapshot.empty(),
                restrictedSnapshot.graphFingerprint(),
                restrictedSnapshot.safetyRadiusMeters(),
                0,
                List.of());
        for (RouteFixture fixture : fixtures) {
            Wgs84Coordinate start = fixture.start().toCoordinate();
            Wgs84Coordinate end = fixture.end().toCoordinate();
            assertThat(graphManager.contains(start)).as(fixture.id() + " start in bounds").isTrue();
            assertThat(graphManager.contains(end)).as(fixture.id() + " end in bounds").isTrue();
            assertThat(validator.isRestricted(start, restrictedSnapshot)).isFalse();
            assertThat(validator.isRestricted(end, restrictedSnapshot)).isFalse();

            EngineRoute unrestricted = engine.route(start, end, unrestrictedSnapshot);
            assertThat(unrestricted.distanceMeters())
                    .as(fixture.id() + " unrestricted distance")
                    .isBetween(fixture.minDistanceMeters(), fixture.maxDistanceMeters());
            assertThatThrownBy(() -> engine.route(start, end, restrictedSnapshot))
                    .isInstanceOfSatisfying(RoutingEngineException.class,
                            exception -> assertThat(exception.reason())
                                    .isEqualTo(RoutingEngineException.Reason.NO_ROUTE));
        }
    }

    private static void assertBusinessError(ThrowingCallable action, ErrorCode expectedCode) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private static RouteRequest request(Wgs84Coordinate start, Wgs84Coordinate end) {
        return new RouteRequest(
                input(start),
                input(end),
                VehicleType.CAR);
    }

    private static InputCoordinate input(Wgs84Coordinate coordinate) {
        return new InputCoordinate(
                coordinate.lng(),
                coordinate.lat(),
                CoordinateSystem.WGS84,
                CoordinateSource.MAP_PICK,
                null,
                null);
    }

    private static void writeRouteReportIfConfigured(
            Path pbf,
            Path cameras,
            String graphFingerprint,
            List<RouteResult> coldResults,
            List<RouteResult> warmResults,
            List<RouteFixture> noCompliantRoutes) throws Exception {
        String configuredOutput = System.getProperty("real.route.report.output");
        if (configuredOutput == null || configuredOutput.isBlank()) {
            return;
        }
        Path output = Path.of(configuredOutput).toAbsolutePath().normalize();
        StringBuilder report = new StringBuilder("# 京津冀路线回归报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append("\n")
                .append("- PBF SHA-256：`").append(Hashing.sha256(pbf)).append("`\n")
                .append("- 摄像头 JSON SHA-256：`").append(Hashing.sha256(cameras)).append("`\n")
                .append("- 图指纹：`").append(graphFingerprint).append("`\n")
                .append("- 产品安全半径：30 米\n\n")
                .append("## 1. 固定路线\n\n")
                .append("| 阶段 | 路线 | 类别 | 距离米 | 时长秒 | 几何点 | 搜索检查边 | 禁行拒绝 | 冲突 |\n")
                .append("|---|---|---|---:|---:|---:|---:|---:|---:|\n");
        List<RouteResult> allResults = new ArrayList<>(coldResults);
        allResults.addAll(warmResults);
        allResults.forEach(result -> report.append("| ")
                .append(result.phase()).append(" | ")
                .append(result.id()).append(" | ")
                .append(result.category()).append(" | ")
                .append(String.format(java.util.Locale.ROOT, "%.2f", result.distanceMeters())).append(" | ")
                .append(result.durationSeconds()).append(" | ")
                .append(result.geometryPoints()).append(" | ")
                .append(result.searchEdgeChecks()).append(" | ")
                .append(result.blockedRejections()).append(" | ")
                .append(result.conflictCount()).append(" |\n"));
        report.append("\n## 2. 异常与缓存\n\n")
                .append("| 场景 | 结果 |\n|---|---|\n")
                .append("| 路网外坐标 | `OUTSIDE_ROUTING_BOUNDS` |\n")
                .append("| 摄像头限制区起点 | `START_IN_RESTRICTED_AREA` |\n")
                .append("| 摄像头限制区终点 | `END_IN_RESTRICTED_AREA` |\n")
                .append("| 当前禁行快照下无合规路线 | `NO_COMPLIANT_ROUTE`：")
                .append(String.join("、", noCompliantRoutes.stream().map(RouteFixture::id).toList()))
                .append(" |\n")
                .append("| 相同缓存、缺少 PBF | 缓存重载成功，固定路线全部零冲突 |\n")
                .append("| 缓存与 PBF 哈希不一致 | 启动被拒绝 |\n\n")
                .append("## 3. 结论边界\n\n")
                .append("- 本报告证明固定坐标在当前输入版本下可达并通过独立零冲突校验。\n")
                .append("- 距离和时长是当前图的回归基线，不代表路线偏好或导航质量已经人工验收。\n")
                .append("- 两个真实无合规路线夹具先证明空禁行集合下可达，再证明当前禁行快照下无路。\n")
                .append("- `NO_COMPLIANT_ROUTE` 的错误映射和无违规降级同时由确定性单元测试覆盖。\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println("JINGJINJI_ROUTE_REPORT=" + output);
    }

    private static AppProperties properties(
            Path pbf,
            Path cameras,
            Path graphCache,
            Path snapshots) {
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
                        pbf.toString(),
                        currentCache.toString(),
                        candidateCache.toString(),
                        profileMode,
                        30, 2, 4,
                        Duration.ofSeconds(10), 1_000_000),
                new AppProperties.Cameras(
                        cameras.toString(), snapshots.toString(), true, 10_000,
                        updateProperties()),
                new AppProperties.Admin(true));
    }

    private record RouteFixtureSet(
            String pbfSha256,
            String cameraSha256,
            List<RouteFixture> routes,
            List<RouteFixture> noCompliantRoutes) {
    }

    private record RouteFixture(
            String id,
            String category,
            FixtureCoordinate start,
            FixtureCoordinate end,
            double minDistanceMeters,
            double maxDistanceMeters) {
    }

    private record FixtureCoordinate(double lng, double lat) {
        Wgs84Coordinate toCoordinate() {
            return new Wgs84Coordinate(lng, lat);
        }
    }

    private record RouteResult(
            String phase,
            String id,
            String category,
            double distanceMeters,
            long durationSeconds,
            int geometryPoints,
            long searchEdgeChecks,
            long blockedRejections,
            int conflictCount) {
    }
}
