package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.validation.RouteSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PMap;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.Completion.MAX_VISITED_STATES;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.Completion.TIMEOUT;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.SearchDirection.FORWARD;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstraPoc.SearchDirection.REVERSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SixthRingDirectionalRouteRegressionPocTest {
    private static final String FIXTURE_RESOURCE =
            "/fixtures/sixth-ring-directional-route-regression.json";
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double PORTAL_TOLERANCE_METERS = 1_000;
    private static final double MAX_JOIN_GAP_METERS = 100;

    @Test
    void validatesDirectionalRoutesAndCacheReloadOnTheCurrentGraph() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real graph, camera, sixth-ring inputs and regression output properties");

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        RegressionFixtureSet fixtures = readFixtures(objectMapper);
        assertThat(Hashing.sha256(configuration.pbf())).isEqualTo(fixtures.pbfSha256());
        assertThat(Hashing.sha256(configuration.cameraJson())).isEqualTo(fixtures.cameraSha256());

        BoundaryData boundary = readBoundary(objectMapper, configuration.boundaryInput());
        AppProperties properties = properties(
                configuration.pbf(), configuration.graphCache(), configuration.cameraJson());

        PhaseResult initial = executePhase(
                "当前缓存",
                properties,
                configuration,
                objectMapper,
                fixtures,
                boundary);
        PhaseResult reloaded = executePhase(
                "缓存重载",
                properties,
                configuration,
                objectMapper,
                fixtures,
                boundary);

        assertThat(reloaded.graphFingerprint()).isEqualTo(initial.graphFingerprint());
        assertThat(reloaded.signatures()).isEqualTo(initial.signatures());
        writeReport(configuration.reportOutput(), configuration, fixtures, initial, reloaded);
        writeGeoJson(objectMapper, configuration.geoJsonOutput(), configuration, fixtures, initial);
    }

    private static PhaseResult executePhase(
            String phase,
            AppProperties properties,
            Configuration configuration,
            ObjectMapper objectMapper,
            RegressionFixtureSet fixtures,
            BoundaryData boundary) throws Exception {
        GraphHopperManager manager = new GraphHopperManager(properties);
        manager.initialize();
        try {
            String graphFingerprint = manager.requireGraphFingerprint();
            CandidateInput candidates = readCandidates(
                    objectMapper, configuration.candidateInput(), graphFingerprint);
            Map<Integer, String> reservedClusters = readReservedClusters(
                    objectMapper,
                    configuration.clusterInput(),
                    graphFingerprint,
                    Set.copyOf(fixtures.reservedReviewClusters()));
            RoutingSnapshot snapshot = new RoutingSnapshotBuilder(
                    properties,
                    manager,
                    new CameraJsonLoader(objectMapper, new CoordinateConverter()),
                    new BlockedEdgeGenerator()).build(configuration.cameraJson());

            HardAvoidingGraphHopper hopper = manager.requireHopper();
            BaseGraph graph = hopper.getBaseGraph();
            BooleanEncodedValue carAccess = hopper.getEncodingManager()
                    .getBooleanEncodedValue("car_access");
            EdgeFilter snapFilter = edge -> edge.get(carAccess) || edge.getReverse(carAccess);
            Weighting profileWeighting = hopper.createWeighting(hopper.getProfile("car"), new PMap());
            assertThat(profileWeighting.hasTurnCosts()).isTrue();
            Weighting distanceWeighting = new DistanceFirstLegalityWeighting(profileWeighting);
            GraphHopperRoutingEngine routingEngine = new GraphHopperRoutingEngine(manager, properties);

            List<RouteRegressionResult> results = new ArrayList<>();
            for (CrossBoundaryFixture fixture : fixtures.crossBoundaryRoutes()) {
                results.add(runCrossBoundaryRoute(
                        phase,
                        fixture,
                        boundary,
                        graph,
                        hopper,
                        snapFilter,
                        distanceWeighting,
                        routingEngine,
                        snapshot,
                        candidates,
                        reservedClusters));
            }
            for (InternalFixture fixture : fixtures.internalRoutes()) {
                results.add(runInternalRoute(
                        phase, fixture, boundary, routingEngine, snapshot));
            }

            Map<String, BusinessSignature> signatures = new LinkedHashMap<>();
            for (RouteRegressionResult result : results) {
                signatures.put(result.id(), new BusinessSignature(
                        result.id(),
                        result.selectedCandidateId(),
                        Math.round(result.totalDistanceMeters()),
                        result.selectedQuadrant(),
                        result.cameraConflictCount()));
            }
            return new PhaseResult(
                    phase,
                    graphFingerprint,
                    snapshot.cameraSnapshot().version(),
                    snapshot.blockedEdges().blockedEdgeVersion(),
                    snapshot.blockedEdges().blockedEdgeCount(),
                    List.copyOf(results),
                    Map.copyOf(signatures));
        } finally {
            manager.close();
        }
    }

    private static RouteRegressionResult runCrossBoundaryRoute(
            String phase,
            CrossBoundaryFixture fixture,
            BoundaryData boundary,
            BaseGraph graph,
            HardAvoidingGraphHopper hopper,
            EdgeFilter snapFilter,
            Weighting distanceWeighting,
            GraphHopperRoutingEngine routingEngine,
            RoutingSnapshot snapshot,
            CandidateInput candidates,
            Map<Integer, String> reservedClusters) {
        Wgs84Coordinate start = fixture.start().toCoordinate();
        Wgs84Coordinate end = fixture.end().toCoordinate();
        Wgs84Coordinate insidePoint = fixture.direction() == BoundaryDirection.OUTBOUND ? start : end;
        Wgs84Coordinate outsidePoint = fixture.direction() == BoundaryDirection.OUTBOUND ? end : start;
        assertInsideOutside(fixture.id(), boundary, insidePoint, outsidePoint);

        RouteSafetyValidator validator = new RouteSafetyValidator();
        assertThat(validator.isRestricted(start, snapshot)).as(fixture.id() + " start restricted").isFalse();
        assertThat(validator.isRestricted(end, snapshot)).as(fixture.id() + " end restricted").isFalse();
        Snap snap = hopper.getLocationIndex().findClosest(
                insidePoint.lat(), insidePoint.lng(), snapFilter);
        assertThat(snap.isValid()).as(fixture.id() + " inside snap").isTrue();
        System.out.printf(
                Locale.ROOT,
                "SIXTH_RING_SNAP id=%s query=%.7f,%.7f snapped=%.7f,%.7f distance=%.1f%n",
                fixture.id(),
                insidePoint.lng(),
                insidePoint.lat(),
                snap.getSnappedPoint().lon,
                snap.getSnappedPoint().lat,
                snap.getQueryDistance());
        List<Candidate> directionalCandidates = fixture.direction() == BoundaryDirection.OUTBOUND
                ? candidates.outbound() : candidates.inbound();
        SearchMeasurement search = search(
                graph,
                distanceWeighting,
                snapshot,
                snap.getClosestNode(),
                directionalCandidates,
                fixture.direction() == BoundaryDirection.OUTBOUND ? FORWARD : REVERSE);
        assertCompleted(fixture.id(), search);
        assertSafeInsideCandidates(fixture.id(), graph, search.result(), candidates, snapshot);

        List<ReferenceOption> references = referenceOptions(
                fixture,
                graph,
                candidates,
                search.result(),
                routingEngine,
                snapshot);
        assertThat(references).as(fixture.id() + " routable portal candidates").isNotEmpty();
        ReferenceOption selected = references.getFirst();
        Candidate candidate = selected.candidate();
        String expectedRole = fixture.direction() == BoundaryDirection.OUTBOUND
                ? "outer_exit" : "inner_entry";
        assertThat(candidate.direction()).isEqualTo(fixture.direction());
        assertThat(candidate.boundaryRole()).isEqualTo(expectedRole);
        Quadrant selectedQuadrant = quadrant(boundary.center(), candidate.coordinate());
        assertThat(selectedQuadrant).as(fixture.id() + " selected quadrant")
                .isEqualTo(fixture.quadrant());
        assertThat(selected.insideDistanceMeters() - search.result().minimumDistanceMeters())
                .as(fixture.id() + " Dmin tolerance")
                .isBetween(0.0, PORTAL_TOLERANCE_METERS + 0.001);
        assertThat(selected.totalDistanceMeters())
                .as(fixture.id() + " total distance")
                .isBetween(fixture.minDistanceMeters(), fixture.maxDistanceMeters());
        assertThat(selected.joinGapMeters())
                .as(fixture.id() + " portal join gap")
                .isLessThanOrEqualTo(MAX_JOIN_GAP_METERS);
        int conflicts = validator.validate(selected.fullGeometry(), snapshot).conflictCount();
        assertThat(conflicts).as(fixture.id() + " full geometry conflicts").isZero();

        String reservedCluster = reservedClusters.get(candidate.edgeKey());
        System.out.printf(
                Locale.ROOT,
                "SIXTH_RING_ROUTE phase=%s id=%s direction=%s portal=%s quadrant=%s "
                        + "distance=%.0f reserved=%s%n",
                phase,
                fixture.id(),
                fixture.direction(),
                candidate.id(),
                selectedQuadrant,
                selected.totalDistanceMeters(),
                reservedCluster == null ? "-" : reservedCluster);
        return new RouteRegressionResult(
                phase,
                fixture.id(),
                "CROSS_BOUNDARY_" + fixture.direction(),
                fixture.quadrant(),
                selectedQuadrant,
                fixture.reviewExpectation(),
                candidate.id(),
                candidate.edgeKey(),
                candidate.candidateType(),
                candidate.boundaryRole(),
                reservedCluster,
                search.result().minimumDistanceMeters(),
                selected.insideDistanceMeters(),
                selected.outsideDistanceMeters(),
                selected.totalDistanceMeters(),
                references.size(),
                search.result().visitedStates(),
                search.elapsed().toNanos() / 1_000_000.0,
                search.audit().blockedRejections(),
                selected.joinGapMeters(),
                conflicts,
                start,
                end,
                candidate.coordinate(),
                selected.fullGeometry());
    }

    private static RouteRegressionResult runInternalRoute(
            String phase,
            InternalFixture fixture,
            BoundaryData boundary,
            GraphHopperRoutingEngine routingEngine,
            RoutingSnapshot snapshot) {
        Wgs84Coordinate start = fixture.start().toCoordinate();
        Wgs84Coordinate end = fixture.end().toCoordinate();
        assertThat(covers(boundary.innerPolygon(), start)).as(fixture.id() + " start inside").isTrue();
        assertThat(covers(boundary.innerPolygon(), end)).as(fixture.id() + " end inside").isTrue();
        RouteSafetyValidator validator = new RouteSafetyValidator();
        assertThat(validator.isRestricted(start, snapshot)).as(fixture.id() + " start restricted").isFalse();
        assertThat(validator.isRestricted(end, snapshot)).as(fixture.id() + " end restricted").isFalse();

        long started = System.nanoTime();
        EngineRoute route = routingEngine.route(start, end, snapshot);
        double elapsedMillis = (System.nanoTime() - started) / 1_000_000.0;
        int conflicts = validator.validate(route.geometry(), snapshot).conflictCount();
        assertThat(conflicts).as(fixture.id() + " camera conflicts").isZero();
        assertThat(route.distanceMeters())
                .as(fixture.id() + " distance")
                .isBetween(fixture.minDistanceMeters(), fixture.maxDistanceMeters());
        assertThat(route.searchEdgeChecks()).as(fixture.id() + " search audit").isPositive();
        return new RouteRegressionResult(
                phase,
                fixture.id(),
                "INTERNAL_SAFE",
                fixture.quadrant(),
                fixture.quadrant(),
                fixture.reviewExpectation(),
                "",
                -1,
                "",
                "",
                null,
                Double.NaN,
                route.distanceMeters(),
                0,
                route.distanceMeters(),
                0,
                0,
                elapsedMillis,
                route.blockedRejections(),
                0,
                conflicts,
                start,
                end,
                null,
                route.geometry());
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
                PORTAL_TOLERANCE_METERS,
                2_000_000,
                Duration.ofSeconds(30));
        return new SearchMeasurement(
                result,
                Duration.ofNanos(System.nanoTime() - started),
                audit);
    }

    private static void assertCompleted(String routeId, SearchMeasurement search) {
        assertThat(search.result().completion()).as(routeId + " completion")
                .isNotIn(TIMEOUT, MAX_VISITED_STATES);
        assertThat(search.result().provenNoRoute()).as(routeId + " route exists").isFalse();
        assertThat(search.result().candidates()).as(routeId + " retained candidates").isNotEmpty();
        assertThat(search.elapsed()).as(routeId + " search performance")
                .isLessThan(Duration.ofSeconds(5));
    }

    private static void assertSafeInsideCandidates(
            String routeId,
            BaseGraph graph,
            EdgeKeyMultiTargetDijkstraPoc.SearchResult result,
            CandidateInput candidates,
            RoutingSnapshot snapshot) {
        RouteSafetyValidator validator = new RouteSafetyValidator();
        for (EdgeKeyMultiTargetDijkstraPoc.PortalPath path : result.candidates().values()) {
            assertThat(path.edgeKeys()).as(routeId + " blocked edge keys")
                    .noneMatch(snapshot.blockedEdges()::isBlockedEdgeKey);
            Candidate candidate = candidates.byId().get(path.portal().id());
            List<Wgs84Coordinate> geometry = insideGeometry(graph, path, candidate);
            assertThat(geometry).as(routeId + " inside geometry").hasSizeGreaterThan(1);
            assertThat(validator.validate(geometry, snapshot).conflictCount())
                    .as(routeId + " portal " + candidate.id() + " conflicts")
                    .isZero();
        }
    }

    private static List<ReferenceOption> referenceOptions(
            CrossBoundaryFixture fixture,
            BaseGraph graph,
            CandidateInput candidates,
            EdgeKeyMultiTargetDijkstraPoc.SearchResult insideResult,
            GraphHopperRoutingEngine routingEngine,
            RoutingSnapshot snapshot) {
        RouteSafetyValidator validator = new RouteSafetyValidator();
        List<ReferenceOption> options = new ArrayList<>();
        for (EdgeKeyMultiTargetDijkstraPoc.PortalPath insidePath
                : insideResult.candidates().values()) {
            Candidate candidate = candidates.byId().get(insidePath.portal().id());
            EngineRoute outsideSegment;
            try {
                outsideSegment = fixture.direction() == BoundaryDirection.OUTBOUND
                        ? routingEngine.route(candidate.coordinate(), fixture.end().toCoordinate(), snapshot)
                        : routingEngine.route(fixture.start().toCoordinate(), candidate.coordinate(), snapshot);
            } catch (RoutingEngineException exception) {
                continue;
            }
            assertThat(validator.validate(outsideSegment.geometry(), snapshot).conflictCount())
                    .as(fixture.id() + " reference conflicts for " + candidate.id())
                    .isZero();
            List<Wgs84Coordinate> insideGeometry = insideGeometry(graph, insidePath, candidate);
            List<Wgs84Coordinate> fullGeometry;
            double joinGap;
            if (fixture.direction() == BoundaryDirection.OUTBOUND) {
                joinGap = GeoDistance.meters(
                        insideGeometry.getLast(), outsideSegment.geometry().getFirst());
                fullGeometry = join(
                        List.of(fixture.start().toCoordinate()),
                        insideGeometry,
                        outsideSegment.geometry());
            } else {
                joinGap = GeoDistance.meters(
                        outsideSegment.geometry().getLast(), insideGeometry.getFirst());
                fullGeometry = join(
                        outsideSegment.geometry(),
                        insideGeometry,
                        List.of(fixture.end().toCoordinate()));
            }
            options.add(new ReferenceOption(
                    candidate,
                    insidePath.distanceMeters(),
                    outsideSegment.distanceMeters(),
                    insidePath.distanceMeters() + outsideSegment.distanceMeters(),
                    outsideSegment.durationMillis(),
                    joinGap,
                    fullGeometry));
        }
        return options.stream()
                .sorted(Comparator.comparingDouble(ReferenceOption::totalDistanceMeters)
                        .thenComparingLong(ReferenceOption::outsideDurationMillis)
                        .thenComparing(option -> option.candidate().id()))
                .toList();
    }

    private static List<Wgs84Coordinate> insideGeometry(
            BaseGraph graph,
            EdgeKeyMultiTargetDijkstraPoc.PortalPath path,
            Candidate candidate) {
        List<Wgs84Coordinate> result = new ArrayList<>();
        List<Integer> edgeKeys = path.edgeKeys();
        for (int index = 0; index < edgeKeys.size(); index++) {
            int edgeKey = edgeKeys.get(index);
            double fromFraction = 0;
            double toFraction = 1;
            if (edgeKey == candidate.edgeKey()) {
                if (candidate.direction() == BoundaryDirection.OUTBOUND && index == edgeKeys.size() - 1) {
                    toFraction = candidate.portal().fractionFromBase();
                } else if (candidate.direction() == BoundaryDirection.INBOUND && index == 0) {
                    fromFraction = candidate.portal().fractionFromBase();
                }
            }
            appendDistinct(result, edgeSlice(graph, edgeKey, fromFraction, toFraction));
        }
        if (!result.isEmpty()) {
            if (candidate.direction() == BoundaryDirection.OUTBOUND) {
                result.set(result.size() - 1, candidate.coordinate());
            } else {
                result.set(0, candidate.coordinate());
            }
        }
        return List.copyOf(result);
    }

    private static List<Wgs84Coordinate> edgeSlice(
            BaseGraph graph,
            int edgeKey,
            double fromFraction,
            double toFraction) {
        EdgeIteratorState edge = graph.getEdgeIteratorStateForKey(edgeKey);
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        if (points.size() < 2) {
            return List.of();
        }
        double[] cumulative = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            cumulative[index] = cumulative[index - 1] + Math.hypot(
                    points.getLon(index) - points.getLon(index - 1),
                    points.getLat(index) - points.getLat(index - 1));
        }
        double total = cumulative[cumulative.length - 1];
        double from = total * fromFraction;
        double to = total * toFraction;
        List<Wgs84Coordinate> result = new ArrayList<>();
        result.add(pointAt(points, cumulative, from));
        for (int index = 1; index < points.size() - 1; index++) {
            if (cumulative[index] > from && cumulative[index] < to) {
                result.add(new Wgs84Coordinate(points.getLon(index), points.getLat(index)));
            }
        }
        result.add(pointAt(points, cumulative, to));
        return List.copyOf(result);
    }

    private static Wgs84Coordinate pointAt(PointList points, double[] cumulative, double target) {
        if (target <= 0) {
            return new Wgs84Coordinate(points.getLon(0), points.getLat(0));
        }
        int last = points.size() - 1;
        if (target >= cumulative[last]) {
            return new Wgs84Coordinate(points.getLon(last), points.getLat(last));
        }
        for (int index = 1; index < points.size(); index++) {
            if (cumulative[index] < target) {
                continue;
            }
            double segment = cumulative[index] - cumulative[index - 1];
            double fraction = segment == 0 ? 0 : (target - cumulative[index - 1]) / segment;
            double lng = points.getLon(index - 1)
                    + (points.getLon(index) - points.getLon(index - 1)) * fraction;
            double lat = points.getLat(index - 1)
                    + (points.getLat(index) - points.getLat(index - 1)) * fraction;
            return new Wgs84Coordinate(lng, lat);
        }
        throw new IllegalStateException("edge slice coordinate was not found");
    }

    @SafeVarargs
    private static List<Wgs84Coordinate> join(List<Wgs84Coordinate>... parts) {
        List<Wgs84Coordinate> result = new ArrayList<>();
        for (List<Wgs84Coordinate> part : parts) {
            appendDistinct(result, part);
        }
        return List.copyOf(result);
    }

    private static void appendDistinct(
            List<Wgs84Coordinate> target,
            List<Wgs84Coordinate> source) {
        for (Wgs84Coordinate point : source) {
            if (target.isEmpty() || !target.getLast().equals(point)) {
                target.add(point);
            }
        }
    }

    private static RegressionFixtureSet readFixtures(ObjectMapper objectMapper) throws Exception {
        try (InputStream stream = SixthRingDirectionalRouteRegressionPocTest.class
                .getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream).isNotNull();
            RegressionFixtureSet fixtures = objectMapper.readValue(stream, RegressionFixtureSet.class);
            assertThat(fixtures.crossBoundaryRoutes()).hasSize(8);
            assertThat(fixtures.internalRoutes()).hasSize(4);
            assertThat(fixtures.crossBoundaryRoutes())
                    .extracting(CrossBoundaryFixture::quadrant)
                    .containsExactlyInAnyOrder(
                            Quadrant.EAST, Quadrant.EAST,
                            Quadrant.SOUTH, Quadrant.SOUTH,
                            Quadrant.WEST, Quadrant.WEST,
                            Quadrant.NORTH, Quadrant.NORTH);
            return fixtures;
        }
    }

    private static CandidateInput readCandidates(
            ObjectMapper objectMapper,
            Path input,
            String expectedFingerprint) throws Exception {
        JsonNode root = objectMapper.readTree(input.toFile());
        assertThat(root.path("graphFingerprint").asText()).isEqualTo(expectedFingerprint);
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (JsonNode feature : root.path("features")) {
            JsonNode properties = feature.path("properties");
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            Candidate candidate = new Candidate(
                    properties.path("candidateId").asText(),
                    BoundaryDirection.valueOf(properties.path("direction").asText()),
                    properties.path("candidateType").asText(),
                    properties.path("boundaryRole").asText(),
                    properties.path("roadName").asText(),
                    new Wgs84Coordinate(coordinates.get(0).asDouble(), coordinates.get(1).asDouble()),
                    new EdgeKeyMultiTargetDijkstraPoc.Portal(
                            properties.path("candidateId").asText(),
                            properties.path("edgeKey").asInt(),
                            properties.path("fractionFromBase").asDouble()));
            byId.put(candidate.id(), candidate);
        }
        return new CandidateInput(Map.copyOf(byId));
    }

    private static Map<Integer, String> readReservedClusters(
            ObjectMapper objectMapper,
            Path input,
            String expectedFingerprint,
            Set<String> reservedIds) throws Exception {
        JsonNode root = objectMapper.readTree(input.toFile());
        assertThat(root.path("graphFingerprint").asText()).isEqualTo(expectedFingerprint);
        Map<Integer, String> clustersByEdgeKey = new LinkedHashMap<>();
        Set<String> found = new LinkedHashSet<>();
        for (JsonNode feature : root.path("features")) {
            String clusterId = feature.path("properties").path("clusterId").asText();
            if (!reservedIds.contains(clusterId)) {
                continue;
            }
            found.add(clusterId);
            String edgeKeys = feature.path("properties").path("edgeKeys").asText();
            for (String edgeKey : edgeKeys.split(",")) {
                clustersByEdgeKey.put(Integer.parseInt(edgeKey), clusterId);
            }
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(reservedIds);
        return Map.copyOf(clustersByEdgeKey);
    }

    private static BoundaryData readBoundary(ObjectMapper objectMapper, Path input) throws Exception {
        JsonNode root = objectMapper.readTree(input.toFile());
        Polygon inner = null;
        Polygon outer = null;
        for (JsonNode feature : root.path("features")) {
            String role = feature.path("properties").path("role").asText();
            Polygon polygon = polygon(feature.path("geometry").path("coordinates"));
            if ("inside_boundary".equals(role)) {
                inner = polygon;
            } else if ("outside_boundary".equals(role)) {
                outer = polygon;
            }
        }
        assertThat(inner).isNotNull();
        assertThat(outer).isNotNull();
        assertThat(outer.covers(inner)).isTrue();
        return new BoundaryData(inner, outer, inner.getCentroid());
    }

    private static Polygon polygon(JsonNode rings) {
        LinearRing shell = linearRing(rings.get(0));
        LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
        for (int index = 1; index < rings.size(); index++) {
            holes[index - 1] = linearRing(rings.get(index));
        }
        return GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static LinearRing linearRing(JsonNode coordinates) {
        List<Coordinate> values = new ArrayList<>();
        for (JsonNode coordinate : coordinates) {
            values.add(new Coordinate(coordinate.get(0).asDouble(), coordinate.get(1).asDouble()));
        }
        return GEOMETRY_FACTORY.createLinearRing(values.toArray(Coordinate[]::new));
    }

    private static void assertInsideOutside(
            String fixtureId,
            BoundaryData boundary,
            Wgs84Coordinate inside,
            Wgs84Coordinate outside) {
        assertThat(covers(boundary.innerPolygon(), inside)).as(fixtureId + " inside point").isTrue();
        assertThat(covers(boundary.outerPolygon(), outside)).as(fixtureId + " outside point").isFalse();
    }

    private static boolean covers(Polygon polygon, Wgs84Coordinate coordinate) {
        return polygon.covers(GEOMETRY_FACTORY.createPoint(
                new Coordinate(coordinate.lng(), coordinate.lat())));
    }

    private static Quadrant quadrant(Point center, Wgs84Coordinate coordinate) {
        double eastMeters = (coordinate.lng() - center.getX())
                * 111_320 * Math.cos(Math.toRadians(center.getY()));
        double northMeters = (coordinate.lat() - center.getY()) * 111_320;
        if (Math.abs(eastMeters) >= Math.abs(northMeters)) {
            return eastMeters >= 0 ? Quadrant.EAST : Quadrant.WEST;
        }
        return northMeters >= 0 ? Quadrant.NORTH : Quadrant.SOUTH;
    }

    private static void writeReport(
            Path output,
            Configuration configuration,
            RegressionFixtureSet fixtures,
            PhaseResult initial,
            PhaseResult reloaded) throws Exception {
        StringBuilder report = new StringBuilder("# 六环东南西北真实路线回归报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append("\n")
                .append("- 图指纹：`").append(initial.graphFingerprint()).append("`\n")
                .append("- PBF SHA-256：`").append(fixtures.pbfSha256()).append("`\n")
                .append("- 摄像头 JSON SHA-256：`").append(fixtures.cameraSha256()).append("`\n")
                .append("- 摄像头快照：`").append(initial.cameraSnapshotVersion()).append("`\n")
                .append("- 禁行边版本：`").append(initial.blockedEdgeVersion()).append("`\n")
                .append("- 禁行基础边：").append(initial.blockedEdgeCount()).append("\n")
                .append("- 候选输入：`").append(configuration.candidateInput()).append("`\n")
                .append("- 人工抽查：20 个复杂位置已检查，16 个初步通过，4 个保留\n")
                .append("- 保留位置：`").append(String.join("`, `", fixtures.reservedReviewClusters()))
                .append("`\n")
                .append("- 生产接入批准：否\n\n")
                .append("## 路线结果\n\n")
                .append("| 阶段 | 路线 | 模式 | 预期/实际象限 | 候选 | Dmin | 环内距离 | 完整距离 | 候选数 | 搜索耗时 | 冲突 | 保留项 |\n")
                .append("|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|\n");
        appendResults(report, initial.results());
        appendResults(report, reloaded.results());
        report.append("\n## 缓存一致性\n\n")
                .append("当前缓存和缓存重载后的候选 ID、路线距离整数米、象限和冲突数完全一致。\n\n")
                .append("## 保留项处理\n\n")
                .append("四个保留位置不从候选集中删除。如果真实路线选中保留位置，表格会显示对应 cluster ID，")
                .append("该路线可以继续用于技术回归，但在生产接入前仍需专项核验。\n\n")
                .append("## 结论边界\n\n")
                .append("- 本报告验证东、南、西、北出环、入环及环内路线的交通合法性、")
                .append("`Dmin + 1000 米` 选择、缓存一致性和摄像头零冲突。\n")
                .append("- GeoJSON 用于人工查看最终路线形状；路线形状尚未人工批准。\n")
                .append("- OpenAPI 1.1 契约已经冻结，生产路线服务尚未接入六环多目标算法。\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println("SIXTH_RING_DIRECTIONAL_REPORT=" + output);
    }

    private static void appendResults(
            StringBuilder report,
            List<RouteRegressionResult> results) {
        for (RouteRegressionResult result : results) {
            report.append("| ").append(result.phase()).append(" | ")
                    .append(result.id()).append(" | ")
                    .append(result.planningMode()).append(" | ")
                    .append(result.expectedQuadrant()).append("/").append(result.selectedQuadrant()).append(" | ")
                    .append(result.selectedCandidateId().isBlank() ? "-" : result.selectedCandidateId())
                    .append(" | ")
                    .append(formatMeters(result.minimumDistanceMeters())).append(" | ")
                    .append(formatMeters(result.insideDistanceMeters())).append(" | ")
                    .append(formatMeters(result.totalDistanceMeters())).append(" | ")
                    .append(result.retainedCandidateCount()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f ms", result.searchElapsedMillis()))
                    .append(" | ").append(result.cameraConflictCount()).append(" | ")
                    .append(result.reservedClusterId() == null ? "-" : result.reservedClusterId())
                    .append(" |\n");
        }
    }

    private static String formatMeters(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.0f m", value) : "-";
    }

    private static void writeGeoJson(
            ObjectMapper objectMapper,
            Path output,
            Configuration configuration,
            RegressionFixtureSet fixtures,
            PhaseResult phase) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("description", "Sixth-ring directional route regression results for manual review");
        root.put("coordinateSystem", "WGS84");
        root.put("graphFingerprint", phase.graphFingerprint());
        root.put("candidateInput", configuration.candidateInput().toString());
        root.put("manualReviewStatus", "16_APPROVED_4_RESERVED");
        root.put("approvedForProduction", false);
        ArrayNode reserved = root.putArray("reservedReviewClusters");
        fixtures.reservedReviewClusters().forEach(reserved::add);
        ArrayNode features = root.putArray("features");
        for (RouteRegressionResult result : phase.results()) {
            ObjectNode routeFeature = features.addObject();
            routeFeature.put("type", "Feature");
            ObjectNode properties = routeFeature.putObject("properties");
            properties.put("featureRole", "ROUTE");
            properties.put("routeId", result.id());
            properties.put("planningMode", result.planningMode());
            properties.put("expectedQuadrant", result.expectedQuadrant().name());
            properties.put("selectedQuadrant", result.selectedQuadrant().name());
            properties.put("reviewExpectation", result.reviewExpectation());
            properties.put("selectedCandidateId", result.selectedCandidateId());
            properties.put("selectedEdgeKey", result.selectedEdgeKey());
            properties.put("candidateType", result.candidateType());
            properties.put("boundaryRole", result.boundaryRole());
            properties.put("reservedClusterId",
                    result.reservedClusterId() == null ? "" : result.reservedClusterId());
            if (Double.isFinite(result.minimumDistanceMeters())) {
                properties.put("minimumDistanceMeters", result.minimumDistanceMeters());
            } else {
                properties.putNull("minimumDistanceMeters");
            }
            properties.put("insideDistanceMeters", result.insideDistanceMeters());
            properties.put("outsideDistanceMeters", result.outsideDistanceMeters());
            properties.put("totalDistanceMeters", result.totalDistanceMeters());
            properties.put("retainedCandidateCount", result.retainedCandidateCount());
            properties.put("visitedStates", result.visitedStates());
            properties.put("searchElapsedMillis", result.searchElapsedMillis());
            properties.put("blockedRejections", result.blockedRejections());
            properties.put("joinGapMeters", result.joinGapMeters());
            properties.put("cameraConflictCount", result.cameraConflictCount());
            ObjectNode geometry = routeFeature.putObject("geometry");
            geometry.put("type", "LineString");
            ArrayNode coordinates = geometry.putArray("coordinates");
            result.geometry().forEach(point -> coordinate(coordinates, point));

            if (result.crossing() != null) {
                ObjectNode crossingFeature = features.addObject();
                crossingFeature.put("type", "Feature");
                ObjectNode crossingProperties = crossingFeature.putObject("properties");
                crossingProperties.put("featureRole", "BOUNDARY_CROSSING");
                crossingProperties.put("routeId", result.id());
                crossingProperties.put("selectedCandidateId", result.selectedCandidateId());
                crossingProperties.put("selectedEdgeKey", result.selectedEdgeKey());
                crossingProperties.put("direction", result.planningMode());
                crossingProperties.put("reservedClusterId",
                        result.reservedClusterId() == null ? "" : result.reservedClusterId());
                ObjectNode crossingGeometry = crossingFeature.putObject("geometry");
                crossingGeometry.put("type", "Point");
                ArrayNode crossingCoordinate = crossingGeometry.putArray("coordinates");
                crossingCoordinate.add(result.crossing().lng());
                crossingCoordinate.add(result.crossing().lat());
            }
        }
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
        System.out.println("SIXTH_RING_DIRECTIONAL_GEOJSON=" + output);
    }

    private static void coordinate(ArrayNode coordinates, Wgs84Coordinate point) {
        ArrayNode coordinate = coordinates.addArray();
        coordinate.add(point.lng());
        coordinate.add(point.lat());
    }

    private static Configuration configuration() {
        String pbf = System.getProperty("real.pbf");
        String graphCache = System.getProperty("real.graph.cache");
        String cameraJson = System.getProperty("real.camera.json");
        String candidates = System.getProperty("sixth.ring.directed.input");
        String boundary = System.getProperty("sixth.ring.boundary.input");
        String clusters = System.getProperty("sixth.ring.clusters.input");
        String report = System.getProperty("sixth.ring.regression.report.output");
        String geoJson = System.getProperty("sixth.ring.regression.geojson.output");
        if (!allConfigured(
                pbf, graphCache, cameraJson, candidates, boundary, clusters, report, geoJson)) {
            return null;
        }
        return new Configuration(
                Path.of(pbf).toAbsolutePath().normalize(),
                Path.of(graphCache).toAbsolutePath().normalize(),
                Path.of(cameraJson).toAbsolutePath().normalize(),
                Path.of(candidates).toAbsolutePath().normalize(),
                Path.of(boundary).toAbsolutePath().normalize(),
                Path.of(clusters).toAbsolutePath().normalize(),
                Path.of(report).toAbsolutePath().normalize(),
                Path.of(geoJson).toAbsolutePath().normalize());
    }

    private static boolean allConfigured(String... values) {
        return java.util.Arrays.stream(values).allMatch(value -> value != null && !value.isBlank());
    }

    private static AppProperties properties(Path pbf, Path graphCache, Path cameraJson) {
        RoutingProfileMode profileMode = RoutingProfileMode.valueOf(
                System.getProperty(
                        "real.routing.profile.mode",
                        RoutingProfileMode.COMPLIANT_DISTANCE_V1.name()));
        assertThat(profileMode).isEqualTo(RoutingProfileMode.COMPLIANT_DISTANCE_V1);
        Path currentCache = graphCache.resolveSibling(graphCache.getFileName() + "-current-reference");
        return new AppProperties(
                new AppProperties.Routing(
                        pbf.toString(), currentCache.toString(), graphCache.toString(), profileMode,
                        30, 2, 4,
                        Duration.ofSeconds(30), 2_000_000),
                new AppProperties.Cameras(
                        cameraJson.toString(), graphCache.resolve("poc-snapshots").toString(),
                        true, 10_000, updateProperties()),
                new AppProperties.Admin(true));
    }

    private enum BoundaryDirection {
        OUTBOUND,
        INBOUND
    }

    private enum Quadrant {
        EAST,
        SOUTH,
        WEST,
        NORTH
    }

    private record Configuration(
            Path pbf,
            Path graphCache,
            Path cameraJson,
            Path candidateInput,
            Path boundaryInput,
            Path clusterInput,
            Path reportOutput,
            Path geoJsonOutput) {
    }

    private record FixtureCoordinate(double lng, double lat) {
        Wgs84Coordinate toCoordinate() {
            return new Wgs84Coordinate(lng, lat);
        }
    }

    private record CrossBoundaryFixture(
            String id,
            Quadrant quadrant,
            BoundaryDirection direction,
            FixtureCoordinate start,
            FixtureCoordinate end,
            double minDistanceMeters,
            double maxDistanceMeters,
            String reviewExpectation) {
    }

    private record InternalFixture(
            String id,
            Quadrant quadrant,
            FixtureCoordinate start,
            FixtureCoordinate end,
            double minDistanceMeters,
            double maxDistanceMeters,
            String reviewExpectation) {
    }

    private record RegressionFixtureSet(
            String pbfSha256,
            String cameraSha256,
            List<String> reservedReviewClusters,
            List<CrossBoundaryFixture> crossBoundaryRoutes,
            List<InternalFixture> internalRoutes) {
    }

    private record BoundaryData(Polygon innerPolygon, Polygon outerPolygon, Point center) {
    }

    private record Candidate(
            String id,
            BoundaryDirection direction,
            String candidateType,
            String boundaryRole,
            String roadName,
            Wgs84Coordinate coordinate,
            EdgeKeyMultiTargetDijkstraPoc.Portal portal) {
        int edgeKey() {
            return portal.edgeKey();
        }
    }

    private record CandidateInput(Map<String, Candidate> byId) {
        List<Candidate> outbound() {
            return byId.values().stream()
                    .filter(candidate -> candidate.direction() == BoundaryDirection.OUTBOUND)
                    .toList();
        }

        List<Candidate> inbound() {
            return byId.values().stream()
                    .filter(candidate -> candidate.direction() == BoundaryDirection.INBOUND)
                    .toList();
        }
    }

    private record SearchMeasurement(
            EdgeKeyMultiTargetDijkstraPoc.SearchResult result,
            Duration elapsed,
            SearchAudit audit) {
    }

    private record ReferenceOption(
            Candidate candidate,
            double insideDistanceMeters,
            double outsideDistanceMeters,
            double totalDistanceMeters,
            long outsideDurationMillis,
            double joinGapMeters,
            List<Wgs84Coordinate> fullGeometry) {
    }

    private record RouteRegressionResult(
            String phase,
            String id,
            String planningMode,
            Quadrant expectedQuadrant,
            Quadrant selectedQuadrant,
            String reviewExpectation,
            String selectedCandidateId,
            int selectedEdgeKey,
            String candidateType,
            String boundaryRole,
            String reservedClusterId,
            double minimumDistanceMeters,
            double insideDistanceMeters,
            double outsideDistanceMeters,
            double totalDistanceMeters,
            int retainedCandidateCount,
            int visitedStates,
            double searchElapsedMillis,
            long blockedRejections,
            double joinGapMeters,
            int cameraConflictCount,
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            Wgs84Coordinate crossing,
            List<Wgs84Coordinate> geometry) {
    }

    private record BusinessSignature(
            String id,
            String selectedCandidateId,
            long roundedDistanceMeters,
            Quadrant selectedQuadrant,
            int conflictCount) {
    }

    private record PhaseResult(
            String phase,
            String graphFingerprint,
            String cameraSnapshotVersion,
            String blockedEdgeVersion,
            int blockedEdgeCount,
            List<RouteRegressionResult> results,
            Map<String, BusinessSignature> signatures) {
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
            return "regression-distance-first|" + delegate.getName();
        }
    }
}
