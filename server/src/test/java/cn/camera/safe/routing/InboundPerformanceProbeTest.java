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
import java.util.Locale;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实数据探针（分支 codex/verify-toll-corridor-inbound）：
 * 对比串行/并行候选评估在入界、出界与朱辛庄→龙意园三条路线上的耗时与结果一致性。
 * 并行评估默认开启时该测试作为回归：两种模式的选中通行口/距离/时间/冲突必须一致。
 */
class InboundPerformanceProbeTest {
    private static final Wgs84Coordinate HUAWEI = new Wgs84Coordinate(116.1892394, 40.060838);
    private static final Wgs84Coordinate LONGYIYUAN = new Wgs84Coordinate(
            117.072047516124, 39.31270167958216);
    private static final Wgs84Coordinate ZHUXINZHUANG = new Wgs84Coordinate(
            116.30328606291512, 40.097249927365986);

    @Test
    void comparesSerialAndParallelCandidateEvaluationOnRealData() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real.pbf, real.graph.cache, real.camera.json and "
                        + "sixth.ring.boundary.input to run the probe");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AppProperties appProperties = appProperties(configuration);
        SixthRingProperties serialProperties = sixthRingProperties(
                configuration.boundary().toString(), false);
        SixthRingProperties parallelProperties = sixthRingProperties(
                configuration.boundary().toString(), true);
        GraphHopperManager graphManager = new GraphHopperManager(appProperties);
        graphManager.initialize();
        try {
            SixthRingRoutingManager sixthRingManager = new SixthRingRoutingManager(
                    graphManager,
                    serialProperties,
                    new SixthRingBoundaryLoader(objectMapper));
            sixthRingManager.initialize();
            RoutingSnapshot snapshot = new RoutingSnapshotBuilder(
                    appProperties,
                    graphManager,
                    new CameraJsonLoader(objectMapper, new CoordinateConverter()),
                    new BlockedEdgeGenerator(),
                    sixthRingManager,
                    serialProperties).build(configuration.cameraJson());
            GraphHopperRoutingEngine routingEngine =
                    new GraphHopperRoutingEngine(graphManager, appProperties);
            SixthRingRoutePlanner serialPlanner = new SixthRingRoutePlanner(
                    graphManager, sixthRingManager, routingEngine, serialProperties);
            SixthRingRoutePlanner parallelPlanner = new SixthRingRoutePlanner(
                    graphManager, sixthRingManager, routingEngine, parallelProperties);

            PlannedRoute inboundSerial = plan(
                    "INBOUND_LYY_HW_SERIAL", serialPlanner, LONGYIYUAN, HUAWEI, snapshot);
            PlannedRoute inboundParallel = plan(
                    "INBOUND_LYY_HW_PARALLEL", parallelPlanner, LONGYIYUAN, HUAWEI, snapshot);
            PlannedRoute outboundSerial = plan(
                    "OUTBOUND_HW_LYY_SERIAL", serialPlanner, HUAWEI, LONGYIYUAN, snapshot);
            PlannedRoute outboundParallel = plan(
                    "OUTBOUND_HW_LYY_PARALLEL", parallelPlanner, HUAWEI, LONGYIYUAN, snapshot);
            PlannedRoute zhuxinzhuangSerial = plan(
                    "OUTBOUND_ZXZ_LYY_SERIAL", serialPlanner, ZHUXINZHUANG, LONGYIYUAN, snapshot);
            PlannedRoute zhuxinzhuangParallel = plan(
                    "OUTBOUND_ZXZ_LYY_PARALLEL", parallelPlanner, ZHUXINZHUANG, LONGYIYUAN, snapshot);
            PlannedRoute lyyZhuxinzhuangSerial = plan(
                    "INBOUND_LYY_ZXZ_SERIAL", serialPlanner, LONGYIYUAN, ZHUXINZHUANG, snapshot);
            PlannedRoute lyyZhuxinzhuangParallel = plan(
                    "INBOUND_LYY_ZXZ_PARALLEL", parallelPlanner, LONGYIYUAN, ZHUXINZHUANG, snapshot);

            assertSameRoute(inboundSerial, inboundParallel, "INBOUND_LYY_HW");
            assertSameRoute(outboundSerial, outboundParallel, "OUTBOUND_HW_LYY");
            assertSameRoute(zhuxinzhuangSerial, zhuxinzhuangParallel, "OUTBOUND_ZXZ_LYY");
            assertSameRoute(lyyZhuxinzhuangSerial, lyyZhuxinzhuangParallel, "INBOUND_LYY_ZXZ");
        } finally {
            graphManager.close();
        }
    }

    private static PlannedRoute plan(
            String label,
            SixthRingRoutePlanner planner,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        long started = System.nanoTime();
        PlannedRoute route = planner.plan(start, end, snapshot);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("PROBE_PLANNED_MS mode=" + label + " value=" + elapsedMillis);
        System.out.println("PROBE_PORTAL mode=" + label + " value="
                + route.boundaryCrossing().id());
        System.out.println("PROBE_DISTANCE mode=" + label + " value="
                + String.format(Locale.ROOT, "%.1f", route.distanceMeters()));
        System.out.println("PROBE_DURATION_S mode=" + label + " value="
                + (route.durationMillis() / 1000.0));
        System.out.println("PROBE_CONFLICT mode=" + label + " value="
                + new RouteSafetyValidator().validate(route, snapshot).conflictCount());
        return route;
    }

    private static void assertSameRoute(
            PlannedRoute serial,
            PlannedRoute parallel,
            String label) {
        if (!serial.boundaryCrossing().id().equals(parallel.boundaryCrossing().id())) {
            throw new AssertionError(label + " portal differs: "
                    + serial.boundaryCrossing().id() + " vs "
                    + parallel.boundaryCrossing().id());
        }
        if (Math.abs(serial.distanceMeters() - parallel.distanceMeters()) > 1) {
            throw new AssertionError(label + " distance differs");
        }
        if (serial.durationMillis() != parallel.durationMillis()) {
            throw new AssertionError(label + " duration differs");
        }
    }

    private static SixthRingProperties sixthRingProperties(
            String boundaryPath,
            boolean parallel) {
        return new SixthRingProperties(
                boundaryPath,
                false,
                50,
                1000,
                100,
                2_000_000,
                Duration.ofSeconds(5),
                parallel,
                4,
                50);
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
        Path probeRoot = Path.of("target", "inbound-probe").toAbsolutePath().normalize();
        return new AppProperties(
                new AppProperties.Routing(
                        configuration.pbf().toString(),
                        probeRoot.resolve("current-cache").toString(),
                        configuration.graphCache().toString(),
                        RoutingProfileMode.COMPLIANT_TIME_V2,
                        50,
                        2,
                        4,
                        Duration.ofSeconds(30),
                        2_000_000),
                new AppProperties.Cameras(
                        configuration.cameraJson().toString(),
                        probeRoot.resolve("snapshots").toString(),
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
