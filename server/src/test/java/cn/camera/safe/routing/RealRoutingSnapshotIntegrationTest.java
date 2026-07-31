package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.camera.CameraLoadResult;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealRoutingSnapshotIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsRealRadiusComparisonsPersistsAtomicallyAndLoadsCacheWithoutPbf() throws Exception {
        String configuredPbf = System.getProperty("real.pbf");
        String configuredCameras = System.getProperty("real.camera.json");
        assumeTrue(configuredPbf != null && !configuredPbf.isBlank()
                        && configuredCameras != null && !configuredCameras.isBlank(),
                "Set -Dreal.pbf and -Dreal.camera.json to run the real snapshot integration test");

        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        Path camerasPath = Path.of(configuredCameras).toAbsolutePath().normalize();
        Path graphCache = temporaryDirectory.resolve("graph-cache");
        Path snapshots = temporaryDirectory.resolve("snapshots");
        AppProperties properties = properties(pbf, camerasPath, graphCache, snapshots);

        GraphHopperManager graphManager = new GraphHopperManager(properties);
        graphManager.initialize();
        try {
            CameraLoadResult loadResult = new CameraJsonLoader(
                    new ObjectMapper(), new CoordinateConverter()).load(camerasPath);
            assertThat(loadResult.isValid()).isTrue();
            CameraSnapshot cameraSnapshot = CameraSnapshot.from(loadResult);
            BlockedEdgeGenerator generator = new BlockedEdgeGenerator();
            BlockedEdgeBuildResult radius20 = generator.generate(
                    cameraSnapshot, graphManager.requireRoadEdgeIndex(), 20);
            BlockedEdgeBuildResult radius30 = generator.generate(
                    cameraSnapshot, graphManager.requireRoadEdgeIndex(), 30);
            BlockedEdgeBuildResult radius50 = generator.generate(
                    cameraSnapshot, graphManager.requireRoadEdgeIndex(), 50);

            assertThat(cameraSnapshot.sourceRecordCount()).isEqualTo(6_797);
            assertThat(cameraSnapshot.retainedRecordCount()).isEqualTo(5_704);
            assertThat(cameraSnapshot.outsideSixRingRecordCount()).isEqualTo(1_093);
            assertThat(cameraSnapshot.unrecognizedSixRingOutRecordCount()).isEqualTo(7);
            assertThat(cameraSnapshot.cameras()).hasSize(5_704);
            assertThat(radius20.snapshot().blockedEdgeCount())
                    .isLessThanOrEqualTo(radius30.snapshot().blockedEdgeCount());
            assertThat(radius30.snapshot().blockedEdgeCount())
                    .isLessThanOrEqualTo(radius50.snapshot().blockedEdgeCount());
            assertThat(radius20.matchedCameraCount())
                    .isLessThanOrEqualTo(radius30.matchedCameraCount());
            assertThat(radius30.matchedCameraCount())
                    .isLessThanOrEqualTo(radius50.matchedCameraCount());
            assertThat(radius30.snapshot().blockedForwardCount())
                    .isEqualTo(radius30.snapshot().blockedReverseCount())
                    .isPositive();
            assertThat(radius30.matchedCameraCount()).isEqualTo(5_685);
            assertThat(radius30.unmatchedCameraIds()).hasSize(19);
            assertThat(radius30.snapshot().blockedEdgeCount()).isEqualTo(19_729);

            RoutingSnapshot routingSnapshot = new RoutingSnapshot(
                    cameraSnapshot,
                    new CameraSpatialIndex(cameraSnapshot.cameras()),
                    radius30.snapshot(),
                    graphManager.requireGraphFingerprint(),
                    30,
                    radius30.matchedCameraCount(),
                    radius30.unmatchedCameraIds());
            Path persisted = new RoutingSnapshotStore(
                    new ObjectMapper().findAndRegisterModules(), properties).persist(routingSnapshot);
            assertThat(persisted).isRegularFile();
            var persistedJson = new ObjectMapper().readTree(persisted.toFile());
            assertThat(persistedJson.path("cameraSourceRecordCount").asInt()).isEqualTo(6_797);
            assertThat(persistedJson.path("cameraRetainedRecordCount").asInt()).isEqualTo(5_704);
            assertThat(persistedJson.path("cameraOutsideSixRingRecordCount").asInt()).isEqualTo(1_093);
            assertThat(persistedJson.path("cameraUnrecognizedSixRingOutRecordCount").asInt()).isEqualTo(7);
            assertThat(persistedJson.path("cameraCount").asInt()).isEqualTo(5_704);

            VerifiedRoute verifiedRoute = findVerifiedRoute(
                    new GraphHopperRoutingEngine(graphManager, properties),
                    new RouteSafetyValidator(),
                    routingSnapshot);
            assertThat(verifiedRoute).isNotNull();

            System.out.printf(
                    "REAL_ROUTING_SNAPSHOT cameras=%d indexedRoadEdges=%d "
                            + "r20Matched=%d r20Blocked=%d r30Matched=%d r30Blocked=%d "
                            + "r50Matched=%d r50Blocked=%d r30Unmatched=%d "
                            + "safeStartLat=%.7f safeStartLon=%.7f safeEndLat=%.7f safeEndLon=%.7f "
                            + "safeDistance=%.1f safePoints=%d safeSearchChecks=%d safeBlockedRejections=%d%n",
                    cameraSnapshot.cameras().size(),
                    graphManager.requireRoadEdgeIndex().indexedEdgeCount(),
                    radius20.matchedCameraCount(), radius20.snapshot().blockedEdgeCount(),
                    radius30.matchedCameraCount(), radius30.snapshot().blockedEdgeCount(),
                    radius50.matchedCameraCount(), radius50.snapshot().blockedEdgeCount(),
                    radius30.unmatchedCameraIds().size(),
                    verifiedRoute.start().lat(), verifiedRoute.start().lng(),
                    verifiedRoute.end().lat(), verifiedRoute.end().lng(),
                    verifiedRoute.route().distanceMeters(),
                    verifiedRoute.route().geometry().size(),
                    verifiedRoute.route().searchEdgeChecks(),
                    verifiedRoute.route().blockedRejections());
        } finally {
            graphManager.close();
        }

        assertThat(Files.isRegularFile(graphCache.resolve("camera-safe-source.sha256"))).isTrue();
        AppProperties cacheOnlyProperties = properties(
                temporaryDirectory.resolve("missing.pbf"), camerasPath, graphCache, snapshots);
        GraphHopperManager cachedManager = new GraphHopperManager(cacheOnlyProperties);
        cachedManager.initialize();
        try {
            assertThat(cachedManager.isReady()).isTrue();
            assertThat(cachedManager.requireGraphFingerprint()).isNotBlank();
        } finally {
            cachedManager.close();
        }
    }

    private static AppProperties properties(
            Path pbf,
            Path cameras,
            Path graphCache,
            Path snapshots) {
        return new AppProperties(
                new AppProperties.Routing(
                        pbf.toString(), graphCache.toString(), 30, 2, 4,
                        Duration.ofSeconds(10), 1_000_000),
                new AppProperties.Cameras(
                        cameras.toString(), snapshots.toString(), true, 10_000, 1),
                new AppProperties.Admin(true));
    }

    private static VerifiedRoute findVerifiedRoute(
            RoutingEngine engine,
            RouteSafetyValidator validator,
            RoutingSnapshot snapshot) {
        List<RouteEndpoints> candidates = List.of(
                endpoints(39.9087, 116.3975, 39.9920, 116.4700),
                endpoints(39.9000, 116.3000, 39.9200, 116.3200),
                endpoints(39.9000, 116.4500, 39.9000, 116.4800),
                endpoints(39.8000, 116.2000, 39.8200, 116.2500),
                endpoints(39.9000, 116.6000, 39.9200, 116.6500));
        for (RouteEndpoints candidate : candidates) {
            if (validator.isRestricted(candidate.start(), snapshot)
                    || validator.isRestricted(candidate.end(), snapshot)) {
                continue;
            }
            try {
                EngineRoute route = engine.route(candidate.start(), candidate.end(), snapshot);
                if (validator.validate(route.geometry(), snapshot).compliant()) {
                    return new VerifiedRoute(candidate.start(), candidate.end(), route);
                }
            } catch (RoutingEngineException ignored) {
                // Try the next fixed candidate; no violating route is retained.
            }
        }
        return null;
    }

    private static RouteEndpoints endpoints(
            double startLat,
            double startLon,
            double endLat,
            double endLon) {
        return new RouteEndpoints(
                new Wgs84Coordinate(startLon, startLat),
                new Wgs84Coordinate(endLon, endLat));
    }

    private record RouteEndpoints(Wgs84Coordinate start, Wgs84Coordinate end) {
    }

    private record VerifiedRoute(Wgs84Coordinate start, Wgs84Coordinate end, EngineRoute route) {
    }
}
