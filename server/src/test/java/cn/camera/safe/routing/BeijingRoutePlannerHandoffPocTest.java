package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实图验证：北京图 + 北京版边界下，跨界路线不再包含界外几何，
 * 界内段与界外交接点（高德导航起点/终点）连续拼接，界外端点可超出北京图。
 */
class BeijingRoutePlannerHandoffPocTest {
    private static final Wgs84Coordinate ZHUXINZHUANG_NANQU =
            new Wgs84Coordinate(116.30328606291512, 40.097249927365986);
    private static final Wgs84Coordinate LONGYIYUAN_WUQING =
            new Wgs84Coordinate(117.072047516124, 39.31270167958216);
    private static final Wgs84Coordinate HUAWEI_RESEARCH_INSTITUTE =
            new Wgs84Coordinate(116.1892394, 40.060838);

    @Test
    void plansCrossBoundaryRoutesWithoutOuterGeometryOnBeijingGraph() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real.pbf, real.graph.cache, real.camera.json and sixth.ring.boundary.input");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AppProperties appProperties = appProperties(configuration);
        SixthRingProperties sixthRingProperties = new SixthRingProperties(
                configuration.boundary().toString(),
                false,
                50,
                1000,
                100,
                2_000_000,
                Duration.ofSeconds(8),
                false,
                4,
                50);
        GraphHopperManager graphManager = new GraphHopperManager(appProperties);
        graphManager.initialize();
        try {
            SixthRingRoutingManager sixthRingManager = new SixthRingRoutingManager(
                    graphManager,
                    sixthRingProperties,
                    new SixthRingBoundaryLoader(objectMapper));
            sixthRingManager.initialize();
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

            assertCrossBoundary(
                    planner,
                    validator,
                    snapshot,
                    sixthRingManager.requireContext(),
                    ZHUXINZHUANG_NANQU,
                    LONGYIYUAN_WUQING,
                    RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND,
                    "朱辛庄→龙意园");
            assertCrossBoundary(
                    planner,
                    validator,
                    snapshot,
                    sixthRingManager.requireContext(),
                    LONGYIYUAN_WUQING,
                    ZHUXINZHUANG_NANQU,
                    RoutePlanningMode.CROSS_BOUNDARY_INBOUND,
                    "龙意园→朱辛庄");
            assertCrossBoundary(
                    planner,
                    validator,
                    snapshot,
                    sixthRingManager.requireContext(),
                    HUAWEI_RESEARCH_INSTITUTE,
                    LONGYIYUAN_WUQING,
                    RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND,
                    "华为→龙意园");
            assertCrossBoundary(
                    planner,
                    validator,
                    snapshot,
                    sixthRingManager.requireContext(),
                    LONGYIYUAN_WUQING,
                    HUAWEI_RESEARCH_INSTITUTE,
                    RoutePlanningMode.CROSS_BOUNDARY_INBOUND,
                    "龙意园→华为");

            PlannedRoute externalOnly = planner.plan(
                    new Wgs84Coordinate(115.97, 40.46),
                    new Wgs84Coordinate(116.85, 40.37),
                    snapshot);
            assertThat(externalOnly.planningMode())
                    .isEqualTo(RoutePlanningMode.EXTERNAL_ONLY);
            assertThat(externalOnly.safeSegment()).isNull();
            assertThat(externalOnly.referenceSegment()).isNull();
            assertThat(externalOnly.navigationHandoff()).isNull();
            assertThat(externalOnly.geometry().size()).isGreaterThanOrEqualTo(2);
            assertThat(externalOnly.distanceMeters()).isGreaterThan(0);
        } finally {
            graphManager.close();
        }
    }

    private static void assertCrossBoundary(
            SixthRingRoutePlanner planner,
            RouteSafetyValidator validator,
            RoutingSnapshot snapshot,
            SixthRingRoutingContext context,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutePlanningMode expectedMode,
            String label) {
        PlannedRoute route = planner.plan(start, end, snapshot);
        assertThat(route.planningMode()).as("%s 模式", label).isEqualTo(expectedMode);
        assertThat(route.safeSegment()).as("%s 界内段", label).isNotNull();
        assertThat(route.referenceSegment()).as("%s 不应返回界外几何", label).isNull();
        assertThat(route.navigationHandoff()).as("%s 交接点", label).isNotNull();
        assertThat(route.externalHandoff()).as("%s 外部交接点", label).isNotNull();
        assertThat(context.boundary().locate(route.navigationHandoff().coordinate()))
                .as("%s 交接点在界外", label)
                .isEqualTo(SixthRingBoundary.Location.OUTSIDE);
        Wgs84Coordinate safeJoin = expectedMode == RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND
                ? route.safeSegment().geometry().getLast()
                : route.safeSegment().geometry().getFirst();
        assertThat(route.navigationHandoff().coordinate())
                .as("%s 交接点与界内段端点连续", label)
                .isEqualTo(safeJoin);
        assertThat(validator.validate(route, snapshot).compliant())
                .as("%s 安全校验", label)
                .isTrue();
        System.out.printf("%s 界内km=%.1f 交接点净空m=%.1f 通行口=%s%n",
                label,
                route.safeSegment().distanceMeters() / 1000.0,
                route.navigationHandoff().boundaryClearanceMeters(),
                route.boundaryCrossing().id());
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
                        configuration.graphCache().resolve("handoff-poc-snapshots").toString(),
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
