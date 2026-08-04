package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SixthRingRoutePlannerIntegrationTest {
    private static final String FIXTURE_RESOURCE =
            "/fixtures/sixth-ring-directional-route-regression.json";

    @Test
    void validatesProductionPlannerAgainstAllDirectionalFixtures() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real graph, camera and sixth-ring boundary properties");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonNode fixtures;
        try (InputStream input = getClass().getResourceAsStream(FIXTURE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + FIXTURE_RESOURCE);
            }
            fixtures = objectMapper.readTree(input);
        }
        assertThat(Hashing.sha256(configuration.pbf()))
                .isEqualTo(fixtures.path("pbfSha256").asText());
        assertThat(Hashing.sha256(configuration.cameraJson()))
                .isEqualTo(fixtures.path("cameraSha256").asText());

        AppProperties appProperties = appProperties(configuration);
        SixthRingProperties sixthRingProperties = new SixthRingProperties(
                configuration.boundary().toString(),
                false,
                1_000,
                100,
                100,
                2_000_000,
                Duration.ofSeconds(5));
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
                    new BlockedEdgeGenerator()).build(configuration.cameraJson());
            SixthRingRoutePlanner planner = new SixthRingRoutePlanner(
                    graphManager,
                    sixthRingManager,
                    new GraphHopperRoutingEngine(graphManager, appProperties),
                    sixthRingProperties);
            RouteSafetyValidator validator = new RouteSafetyValidator();
            Point center = sixthRingManager.requireContext().boundary()
                    .innerPolygon().getCentroid();
            List<String> signatures = new ArrayList<>();

            for (JsonNode fixture : fixtures.path("crossBoundaryRoutes")) {
                String id = fixture.path("id").asText();
                SixthRingPortal.Direction direction = SixthRingPortal.Direction.valueOf(
                        fixture.path("direction").asText());
                PlannedRoute route = planner.plan(
                        coordinate(fixture.path("start")),
                        coordinate(fixture.path("end")),
                        snapshot);

                assertThat(route.planningMode()).as(id + " mode").isEqualTo(
                        direction == SixthRingPortal.Direction.OUTBOUND
                                ? RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND
                                : RoutePlanningMode.CROSS_BOUNDARY_INBOUND);
                assertThat(route.boundaryDirection()).as(id + " direction").isEqualTo(direction);
                assertThat(route.boundaryCrossing()).as(id + " portal").isNotNull();
                assertThat(route.safeSegment()).as(id + " safe segment").isNotNull();
                assertThat(route.referenceSegment()).as(id + " reference segment").isNotNull();
                assertThat(route.distanceMeters()).as(id + " distance").isBetween(
                        fixture.path("minDistanceMeters").asDouble(),
                        fixture.path("maxDistanceMeters").asDouble());
                assertThat(validator.validate(route.safeSegment().geometry(), snapshot).conflictCount())
                        .as(id + " safe segment conflicts").isZero();
                assertThat(route.geometry()).as(id + " complete geometry").hasSizeGreaterThan(1);
                assertThat(quadrant(center, route.boundaryCrossing().crossing()))
                        .as(id + " selected quadrant")
                        .isEqualTo(fixture.path("quadrant").asText());
                signatures.add(id + ":" + route.boundaryCrossing().id()
                        + ":" + Math.round(route.distanceMeters()));
            }

            for (JsonNode fixture : fixtures.path("internalRoutes")) {
                String id = fixture.path("id").asText();
                PlannedRoute route = planner.plan(
                        coordinate(fixture.path("start")),
                        coordinate(fixture.path("end")),
                        snapshot);

                assertThat(route.planningMode()).as(id + " mode")
                        .isEqualTo(RoutePlanningMode.INTERNAL_SAFE);
                assertThat(route.boundaryCrossing()).isNull();
                assertThat(route.safeSegment()).isNotNull();
                assertThat(route.referenceSegment()).isNull();
                assertThat(route.distanceMeters()).as(id + " distance").isBetween(
                        fixture.path("minDistanceMeters").asDouble(),
                        fixture.path("maxDistanceMeters").asDouble());
                assertThat(validator.validate(route.safeSegment().geometry(), snapshot).conflictCount())
                        .as(id + " conflicts").isZero();
                signatures.add(id + ":INTERNAL:" + Math.round(route.distanceMeters()));
            }

            assertThat(signatures).hasSize(12);
            signatures.forEach(signature -> System.out.println(
                    "PRODUCTION_SIXTH_RING_ROUTE " + signature));
        } finally {
            graphManager.close();
        }
    }

    private static Wgs84Coordinate coordinate(JsonNode value) {
        return new Wgs84Coordinate(value.path("lng").asDouble(), value.path("lat").asDouble());
    }

    private static String quadrant(Point center, Wgs84Coordinate coordinate) {
        double eastMeters = (coordinate.lng() - center.getX())
                * 111_320 * Math.cos(Math.toRadians(center.getY()));
        double northMeters = (coordinate.lat() - center.getY()) * 111_320;
        if (Math.abs(eastMeters) >= Math.abs(northMeters)) {
            return eastMeters >= 0 ? "EAST" : "WEST";
        }
        return northMeters >= 0 ? "NORTH" : "SOUTH";
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
                        RoutingProfileMode.COMPLIANT_DISTANCE_V1,
                        30,
                        2,
                        4,
                        Duration.ofSeconds(30),
                        2_000_000),
                new AppProperties.Cameras(
                        configuration.cameraJson().toString(),
                        configuration.graphCache().resolve("production-planner-test-snapshots").toString(),
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
