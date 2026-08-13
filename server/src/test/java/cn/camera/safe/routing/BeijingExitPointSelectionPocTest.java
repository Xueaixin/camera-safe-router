package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraJsonLoader;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadClassLink;
import com.graphhopper.routing.ev.RoadEnvironment;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.INTERRUPTED;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.MAX_VISITED_STATES;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.Completion.TIMEOUT;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.FORWARD;
import static cn.camera.safe.routing.EdgeKeyMultiTargetDijkstra.SearchDirection.REVERSE;
import static cn.camera.safe.routing.SixthRingBoundary.Location.OUTSIDE;
import static cn.camera.safe.routing.SixthRingPortal.Direction.INBOUND;
import static cn.camera.safe.routing.SixthRingPortal.Direction.OUTBOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * POC：北京图 + 高德界外拼接 —— 出界点选择与界内外拼接验证。
 *
 * <p>验证目标：
 * <ol>
 *   <li>去掉界外 A* 后，能否用「界内多目标搜索 + 方向过滤 + 直线距离评分」选出合理出界/入界点；</li>
 *   <li>界内段能否连续组装到通行口；</li>
 *   <li>能否从通行口所在边界边向界外延伸出高德导航交接点（界外、净空≥250m、沿路≤500m）；</li>
 *   <li>界外端点在图内时，新选点与现行「完整参考路线 A* 评估」选点对比。</li>
 * </ol>
 *
 * <p>运行参数（系统属性）：
 * {@code real.pbf}、{@code real.graph.cache}、{@code real.camera.json}、
 * {@code sixth.ring.boundary.input}、{@code sixth.ring.exit.report.output}。
 */
class BeijingExitPointSelectionPocTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double DIRECTION_THRESHOLD_DEGREES = 90;
    private static final double HANDOFF_TARGET_CLEARANCE_METERS = 250;
    private static final double HANDOFF_MAX_ROUTE_DISTANCE_METERS = 500;
    private static final double HANDOFF_SAMPLE_STEP_METERS = 25;
    private static final int MAX_TIERS = 5;

    private static final Wgs84Coordinate ZHUXINZHUANG_NANQU =
            new Wgs84Coordinate(116.30328606291512, 40.097249927365986);
    private static final Wgs84Coordinate LONGYIYUAN_WUQING =
            new Wgs84Coordinate(117.072047516124, 39.31270167958216);
    private static final Wgs84Coordinate HUAWEI_RESEARCH_INSTITUTE =
            new Wgs84Coordinate(116.1892394, 40.060838);

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("east-outbound", OUTBOUND,
                    new Wgs84Coordinate(116.5999995, 39.9000364),
                    new Wgs84Coordinate(116.8170, 39.9490),
                    "东向出界（燕郊方向，界外端点超出北京图）"),
            new Scenario("east-inbound", INBOUND,
                    new Wgs84Coordinate(116.5999995, 39.9000364),
                    new Wgs84Coordinate(116.8170, 39.9490),
                    "东向入界（燕郊方向）"),
            new Scenario("south-outbound", OUTBOUND,
                    new Wgs84Coordinate(116.3981473, 39.7523199),
                    new Wgs84Coordinate(116.3190, 39.5160),
                    "南向出界（廊坊方向，界外端点超出北京图）"),
            new Scenario("south-inbound", INBOUND,
                    new Wgs84Coordinate(116.3981473, 39.7523199),
                    new Wgs84Coordinate(116.3190, 39.5160),
                    "南向入界（廊坊方向）"),
            new Scenario("west-outbound", OUTBOUND,
                    new Wgs84Coordinate(116.2009058, 39.9002488),
                    new Wgs84Coordinate(116.0830, 39.6810),
                    "西南向出界（房山方向，界外端点在北京图内）"),
            new Scenario("west-inbound", INBOUND,
                    new Wgs84Coordinate(116.2009058, 39.9002488),
                    new Wgs84Coordinate(116.0830, 39.6810),
                    "西南向入界（房山方向）"),
            new Scenario("north-outbound", OUTBOUND,
                    new Wgs84Coordinate(116.3996417, 40.1024546),
                    new Wgs84Coordinate(116.0080, 40.3590),
                    "北向出界（延庆方向，界外端点在北京图内）"),
            new Scenario("north-inbound", INBOUND,
                    new Wgs84Coordinate(116.3996417, 40.1024546),
                    new Wgs84Coordinate(116.0080, 40.3590),
                    "北向入界（延庆方向）"),
            new Scenario("zhuxinzhuang-outbound", OUTBOUND,
                    ZHUXINZHUANG_NANQU, LONGYIYUAN_WUQING,
                    "朱辛庄新区南区→武清区龙意园（界外端点超出北京图）"),
            new Scenario("longyiyuan-inbound", INBOUND,
                    ZHUXINZHUANG_NANQU, LONGYIYUAN_WUQING,
                    "武清区龙意园→朱辛庄新区南区（界外端点超出北京图）"),
            new Scenario("huawei-outbound", OUTBOUND,
                    HUAWEI_RESEARCH_INSTITUTE, LONGYIYUAN_WUQING,
                    "华为北京研究所→武清区龙意园（界外端点超出北京图）"),
            new Scenario("longyiyuan-huawei-inbound", INBOUND,
                    HUAWEI_RESEARCH_INSTITUTE, LONGYIYUAN_WUQING,
                    "武清区龙意园→华为北京研究所（界外端点超出北京图）"));

    @Test
    void selectsExitPointsWithDirectionAndStraightLineScoring() throws Exception {
        Configuration configuration = configuration();
        assumeTrue(configuration != null,
                "Set real.pbf, real.graph.cache, real.camera.json, "
                        + "sixth.ring.boundary.input and sixth.ring.exit.report.output");

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
            SixthRingRoutingContext context = sixthRingManager.requireContext();

            List<ScenarioResult> results = new ArrayList<>();
            for (Scenario scenario : SCENARIOS) {
                results.add(evaluate(scenario, configuration, graphManager, context, snapshot));
            }
            long elapsedMillis = results.stream()
                    .mapToLong(ScenarioResult::elapsedMillis)
                    .sum();
            writeReport(configuration.report(), configuration, graphManager, results, elapsedMillis);

            for (ScenarioResult result : results) {
                assertThat(result.selectedPortalId())
                        .as("%s 应选出出界/入界点", result.scenario().id())
                        .isNotBlank();
                assertThat(result.insideLegOk())
                        .as("%s 界内段应连续组装", result.scenario().id())
                        .isTrue();
                assertThat(result.handoffOk())
                        .as("%s 界外交接点应位于边界外", result.scenario().id())
                        .isTrue();
            }
        } finally {
            graphManager.close();
        }
    }

    private static ScenarioResult evaluate(
            Scenario scenario,
            Configuration configuration,
            GraphHopperManager graphManager,
            SixthRingRoutingContext context,
            RoutingSnapshot snapshot) {
        long started = System.nanoTime();
        ScenarioResult.Builder builder = ScenarioResult.builder(scenario);
        HardAvoidingGraphHopper hopper = graphManager.requireHopper();
        BaseGraph baseGraph = hopper.getBaseGraph();
        BooleanEncodedValue carAccess = hopper.getEncodingManager()
                .getBooleanEncodedValue("car_access");
        EnumEncodedValue<RoadClass> roadClass = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadClass.KEY, RoadClass.class);
        BooleanEncodedValue roadClassLink = hopper.getEncodingManager()
                .getBooleanEncodedValue(RoadClassLink.KEY);
        EnumEncodedValue<RoadEnvironment> roadEnvironment = hopper.getEncodingManager()
                .getEnumEncodedValue(RoadEnvironment.KEY, RoadEnvironment.class);
        EdgeFilter snapFilter = edge -> edge.get(carAccess) || edge.getReverse(carAccess);
        Wgs84Coordinate insidePoint = scenario.inside();
        Snap snap = hopper.getLocationIndex().findClosest(
                insidePoint.lat(), insidePoint.lng(), snapFilter);
        if (!snap.isValid() || snap.getQueryDistance() > 100) {
            return builder.failure("界内点无法吸附到北京驾车路网")
                    .elapsedMillis((System.nanoTime() - started) / 1_000_000)
                    .build();
        }

        QueryGraph queryGraph = QueryGraph.create(baseGraph, snap);
        Weighting queryTimeWeighting = queryGraph.wrapWeighting(context.controlledTimeWeighting());
        SearchAudit audit = new SearchAudit();
        Weighting blockedWeighting = new BlockedEdgeWeighting(
                queryTimeWeighting,
                snapshot.blockedEdges(),
                audit,
                baseGraph.getEdges());
        List<ControlReleasePoint> releasePoints = new ArrayList<>(
                scenario.direction() == OUTBOUND
                        ? context.releaseTopology().outbound()
                        : context.releaseTopology().inbound());
        Map<String, ControlReleasePoint> releasesById = new HashMap<>();
        List<EdgeKeyMultiTargetDijkstra.Portal> searchPortals = releasePoints.stream()
                .peek(point -> releasesById.put(point.id(), point))
                .map(point -> new EdgeKeyMultiTargetDijkstra.Portal(
                        point.id(), point.edgeKey(), point.fractionFromBase(), point.coordinate()))
                .toList();
        EdgeTraversalConstraint insideConstraint = RoadStateTraversalConstraint.controlled(
                context.boundary().controlledArea(),
                baseGraph.getEdges(),
                context.roadClassification());

        Set<String> evaluatedPortalIds = new HashSet<>();
        List<EdgeKeyMultiTargetDijkstra.PortalPath> accumulated = new ArrayList<>();
        EdgeKeyMultiTargetDijkstra.PortalPath selected = null;
        ControlReleasePoint selectedRelease = null;
        int selectedTier = 0;
        int directionFilteredCount = 0;
        for (int tier = 1; tier <= MAX_TIERS && selected == null; tier++) {
            double toleranceMeters = 1000.0 * tier;
            EdgeKeyMultiTargetDijkstra.SearchResult search = EdgeKeyMultiTargetDijkstra.search(
                    queryGraph,
                    blockedWeighting,
                    snap.getClosestNode(),
                    searchPortals,
                    scenario.direction() == OUTBOUND ? FORWARD : REVERSE,
                    insideConstraint,
                    toleranceMeters,
                    2_000_000,
                    Duration.ofSeconds(8));
            if (search.completion() == TIMEOUT
                    || search.completion() == INTERRUPTED
                    || search.completion() == MAX_VISITED_STATES) {
                builder.failure("界内多目标搜索未完成：" + search.completion());
                break;
            }
            List<EdgeKeyMultiTargetDijkstra.PortalPath> newCandidates =
                    search.candidates().values().stream()
                            .filter(candidate -> evaluatedPortalIds.add(
                                    candidate.portal().id()))
                            .toList();
            accumulated.addAll(newCandidates);
            List<ScoredCandidate> filtered = filterByDirection(
                    scenario, newCandidates, releasesById);
            if (!filtered.isEmpty()) {
                selectedTier = tier;
                directionFilteredCount = filtered.size();
                ScoredCandidate best = filtered.stream()
                        .min(Comparator.comparingDouble(ScoredCandidate::score)
                                .thenComparingDouble(candidate -> candidate.portalPath()
                                        .distanceMeters())
                                .thenComparing(candidate -> candidate.releasePoint().id()))
                        .orElseThrow();
                selected = best.portalPath();
                selectedRelease = best.releasePoint();
            }
        }
        if (selected == null) {
            selected = fallbackSelection(scenario, accumulated, releasesById);
            if (selected != null) {
                selectedRelease = releasesById.get(selected.portal().id());
                directionFilteredCount = 0;
            }
        }
        if (selected == null || selectedRelease == null) {
            return builder.failure("全部层均未选出方向合理且可达的通行口")
                    .elapsedMillis((System.nanoTime() - started) / 1_000_000)
                    .build();
        }

        builder.selectedPortalId(selectedRelease.id())
                .selectedPortalName(selectedRelease.roadName())
                .selectedPortalCoordinate(selectedRelease.coordinate())
                .selectedTier(selectedTier)
                .directionFilteredCount(directionFilteredCount)
                .directionDeltaDegrees(bearingDeltaDegrees(
                        scenario, selectedRelease))
                .insideDistanceMeters(selected.distanceMeters())
                .straightLineDistanceMeters(straightLineMeters(
                        scenario, selectedRelease));

        TracedRouteLeg safeRoute;
        try {
            safeRoute = RouteGeometryAssembler.insideLeg(
                    queryGraph,
                    queryTimeWeighting,
                    selected,
                    scenario.direction(),
                    baseGraph.getEdges(),
                    roadClass,
                    roadClassLink,
                    roadEnvironment);
            builder.insideLegOk(true)
                    .insideLegDistanceMeters(safeRoute.leg().distanceMeters())
                    .insideLegPoints(safeRoute.leg().geometry().size());
        } catch (IllegalArgumentException exception) {
            builder.insideLegOk(false).insideLegError(exception.getMessage());
        }

        HandoffPoint handoff = outerHandoff(
                baseGraph, context.boundary(), selectedRelease);
        builder.handoffCoordinate(handoff.coordinate())
                .handoffRoadName(handoff.roadName())
                .handoffDistanceFromCrossingMeters(handoff.distanceFromCrossingMeters())
                .handoffClearanceMeters(handoff.clearanceMeters())
                .handoffOk(handoff.coordinate() != null
                        && context.boundary().locate(handoff.coordinate()) == OUTSIDE);

        if (outerEndpointInGraph(hopper, scenario.outside(), snapFilter)) {
            builder.outerEndpointInGraph(true);
            compareWithFullReferenceEvaluation(
                    builder,
                    scenario,
                    hopper,
                    context,
                    snapshot,
                    releasesById,
                    accumulated,
                    selectedRelease);
        } else {
            builder.outerEndpointInGraph(false)
                    .fullReferenceNote("界外端点超出当前图范围，现行界外 A* 无法评估（这正是新方案的价值）");
        }
        return builder.elapsedMillis((System.nanoTime() - started) / 1_000_000).build();
    }

    private static EdgeKeyMultiTargetDijkstra.PortalPath fallbackSelection(
            Scenario scenario,
            List<EdgeKeyMultiTargetDijkstra.PortalPath> accumulated,
            Map<String, ControlReleasePoint> releasesById) {
        List<ScoredCandidate> relaxed = filterByDirection(
                scenario, accumulated, releasesById, 180);
        return relaxed.stream()
                .min(Comparator.comparingDouble(ScoredCandidate::score)
                        .thenComparingDouble(candidate -> candidate.portalPath()
                                .distanceMeters()))
                .map(ScoredCandidate::portalPath)
                .orElse(null);
    }

    private static void compareWithFullReferenceEvaluation(
            ScenarioResult.Builder builder,
            Scenario scenario,
            HardAvoidingGraphHopper hopper,
            SixthRingRoutingContext context,
            RoutingSnapshot snapshot,
            Map<String, ControlReleasePoint> releasesById,
            List<EdgeKeyMultiTargetDijkstra.PortalPath> accumulated,
            ControlReleasePoint selectedRelease) {
        EdgeTraversalConstraint referenceConstraint = RoadStateTraversalConstraint.released(
                context.boundary().controlledArea(),
                hopper.getBaseGraph().getEdges(),
                context.roadClassification());
        List<FullReferenceOption> options = new ArrayList<>();
        List<ScoredCandidate> directionFiltered = filterByDirection(
                scenario, accumulated, releasesById);
        List<ScoredCandidate> candidates = directionFiltered.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score))
                .limit(10)
                .toList();
        for (ScoredCandidate candidate : candidates) {
            ControlReleasePoint releasePoint = candidate.releasePoint();
            try {
                PortalAnchoredRoute anchoredRoute = PortalAnchoredRouteFinder.route(
                        hopper,
                        releasePoint,
                        scenario.outside(),
                        snapshot,
                        referenceConstraint,
                        context.routingWeighting(),
                        2_000_000,
                        Duration.ofSeconds(8));
                options.add(new FullReferenceOption(
                        releasePoint.id(),
                        anchoredRoute.route().distanceMeters(),
                        anchoredRoute.route().durationMillis()));
            } catch (RoutingEngineException exception) {
                options.add(new FullReferenceOption(
                        releasePoint.id(), -1, -1));
            }
        }
        FullReferenceOption astarBest = options.stream()
                .filter(option -> option.durationMillis() >= 0)
                .min(Comparator.comparingLong(FullReferenceOption::durationMillis)
                        .thenComparingDouble(FullReferenceOption::distanceMeters))
                .orElse(null);
        if (astarBest != null) {
            builder.fullReferenceBestPortalId(astarBest.portalId())
                    .fullReferenceBestDistanceMeters(astarBest.distanceMeters())
                    .fullReferenceBestDurationMillis(astarBest.durationMillis());
        }
        builder.fullReferenceEvaluatedCount(options.size())
                .fullReferenceNote("A* 完整参考路线评估（时长优先）仅对界外端点在图内的场景执行；"
                        + "用于对照新方案选点");
        if (astarBest == null) {
            builder.fullReferenceNote("全部候选的界外 A* 均失败（界外不可达），"
                    + "说明该方向现行方案在图上无法给出参考路线");
        }
    }

    private static List<ScoredCandidate> filterByDirection(
            Scenario scenario,
            List<EdgeKeyMultiTargetDijkstra.PortalPath> candidates,
            Map<String, ControlReleasePoint> releasesById) {
        return filterByDirection(scenario, candidates, releasesById, DIRECTION_THRESHOLD_DEGREES);
    }

    private static List<ScoredCandidate> filterByDirection(
            Scenario scenario,
            List<EdgeKeyMultiTargetDijkstra.PortalPath> candidates,
            Map<String, ControlReleasePoint> releasesById,
            double thresholdDegrees) {
        return candidates.stream()
                .map(candidate -> new ScoredCandidate(
                        candidate,
                        releasesById.get(candidate.portal().id()),
                        score(scenario, candidate,
                                releasesById.get(candidate.portal().id())),
                        bearingDeltaDegrees(
                                scenario, releasesById.get(candidate.portal().id()))))
                .filter(candidate -> candidate.directionDeltaDegrees() <= thresholdDegrees)
                .toList();
    }

    private static double score(
            Scenario scenario,
            EdgeKeyMultiTargetDijkstra.PortalPath candidate,
            ControlReleasePoint releasePoint) {
        if (releasePoint == null) {
            return Double.POSITIVE_INFINITY;
        }
        return candidate.distanceMeters() + straightLineMeters(scenario, releasePoint);
    }

    private static double straightLineMeters(
            Scenario scenario, ControlReleasePoint releasePoint) {
        return scenario.direction() == OUTBOUND
                ? GeoDistance.meters(releasePoint.coordinate(), scenario.outside())
                : GeoDistance.meters(scenario.outside(), releasePoint.coordinate());
    }

    private static double bearingDeltaDegrees(
            Scenario scenario, ControlReleasePoint releasePoint) {
        if (releasePoint == null) {
            return 180;
        }
        double referenceBearing = scenario.direction() == OUTBOUND
                ? bearingDegrees(scenario.inside(), scenario.outside())
                : bearingDegrees(scenario.outside(), scenario.inside());
        double candidateBearing = scenario.direction() == OUTBOUND
                ? bearingDegrees(scenario.inside(), releasePoint.coordinate())
                : bearingDegrees(scenario.outside(), releasePoint.coordinate());
        double delta = Math.abs(normalizeDegrees(referenceBearing - candidateBearing));
        return Math.min(delta, 360 - delta);
    }

    private static double bearingDegrees(
            Wgs84Coordinate from, Wgs84Coordinate to) {
        double lat1 = Math.toRadians(from.lat());
        double lat2 = Math.toRadians(to.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        return normalizeDegrees(Math.toDegrees(Math.atan2(y, x)));
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private static HandoffPoint outerHandoff(
            BaseGraph baseGraph,
            SixthRingBoundary boundary,
            ControlReleasePoint releasePoint) {
        EdgeIteratorState edge = baseGraph.getEdgeIteratorStateForKey(
                releasePoint.edgeKey());
        if (edge == null) {
            return new HandoffPoint(null, "", 0, -1);
        }
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        List<Wgs84Coordinate> geometry = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            geometry.add(new Wgs84Coordinate(points.getLon(index), points.getLat(index)));
        }
        Wgs84Coordinate crossing = releasePoint.coordinate();
        double[] cumulative = cumulativeDistances(geometry);
        double crossingDistance = distanceAlongGeometry(geometry, cumulative, crossing);
        Geometry controlledArea = boundary.controlledArea();
        Wgs84Coordinate best = null;
        double bestClearance = -1;
        double bestDistanceFromCrossing = 0;
        String roadName = edge.getName();
        for (int index = 1; index < geometry.size(); index++) {
            Wgs84Coordinate from = geometry.get(index - 1);
            Wgs84Coordinate to = geometry.get(index);
            double segmentLength = GeoDistance.meters(from, to);
            if (segmentLength <= 0) {
                continue;
            }
            int samples = (int) Math.max(1, Math.ceil(
                    segmentLength / HANDOFF_SAMPLE_STEP_METERS));
            for (int sample = 1; sample <= samples; sample++) {
                double fraction = (double) sample / samples;
                Wgs84Coordinate point = interpolate(from, to, fraction);
                double fromCrossing = Math.abs(
                        cumulative[index - 1] + segmentLength * fraction
                                - crossingDistance);
                if (fromCrossing > HANDOFF_MAX_ROUTE_DISTANCE_METERS) {
                    continue;
                }
                Point jtsPoint = GEOMETRY_FACTORY.createPoint(
                        new Coordinate(point.lng(), point.lat()));
                if (controlledArea.covers(jtsPoint)) {
                    continue;
                }
                Coordinate nearest = DistanceOp.nearestPoints(
                        controlledArea.getBoundary(), jtsPoint)[0];
                double clearance = GeoDistance.meters(
                        point, new Wgs84Coordinate(nearest.x, nearest.y));
                if (fromCrossing >= HANDOFF_TARGET_CLEARANCE_METERS
                        && clearance >= HANDOFF_TARGET_CLEARANCE_METERS) {
                    return new HandoffPoint(point, roadName, fromCrossing, clearance);
                }
                if (clearance > bestClearance) {
                    bestClearance = clearance;
                    best = point;
                    bestDistanceFromCrossing = fromCrossing;
                }
            }
        }
        return best == null
                ? new HandoffPoint(null, roadName, 0, -1)
                : new HandoffPoint(best, roadName, bestDistanceFromCrossing, bestClearance);
    }

    private static double distanceAlongGeometry(
            List<Wgs84Coordinate> geometry,
            double[] cumulative,
            Wgs84Coordinate target) {
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 1; index < geometry.size(); index++) {
            Wgs84Coordinate from = geometry.get(index - 1);
            Wgs84Coordinate to = geometry.get(index);
            double segmentLength = GeoDistance.meters(from, to);
            if (segmentLength <= 0) {
                continue;
            }
            double fraction = projectFraction(from, to, target);
            Wgs84Coordinate projected = interpolate(from, to, fraction);
            double distanceToProjected = GeoDistance.meters(target, projected);
            if (distanceToProjected < bestDistance) {
                bestDistance = distanceToProjected;
            }
        }
        double bestOffset = Double.POSITIVE_INFINITY;
        for (int index = 1; index < geometry.size(); index++) {
            Wgs84Coordinate from = geometry.get(index - 1);
            Wgs84Coordinate to = geometry.get(index);
            double segmentLength = GeoDistance.meters(from, to);
            if (segmentLength <= 0) {
                continue;
            }
            double fraction = projectFraction(from, to, target);
            Wgs84Coordinate projected = interpolate(from, to, fraction);
            double distanceToProjected = GeoDistance.meters(target, projected);
            if (Math.abs(distanceToProjected - bestDistance) < 1e-6) {
                bestOffset = cumulative[index - 1] + segmentLength * fraction;
                break;
            }
        }
        return bestOffset;
    }

    private static double projectFraction(
            Wgs84Coordinate from, Wgs84Coordinate to, Wgs84Coordinate target) {
        double dLng = to.lng() - from.lng();
        double dLat = to.lat() - from.lat();
        double lengthSquared = dLng * dLng + dLat * dLat;
        if (lengthSquared <= 0) {
            return 0;
        }
        double projection = ((target.lng() - from.lng()) * dLng
                + (target.lat() - from.lat()) * dLat) / lengthSquared;
        return Math.max(0, Math.min(1, projection));
    }

    private static Wgs84Coordinate interpolate(
            Wgs84Coordinate from, Wgs84Coordinate to, double fraction) {
        return new Wgs84Coordinate(
                from.lng() + (to.lng() - from.lng()) * fraction,
                from.lat() + (to.lat() - from.lat()) * fraction);
    }

    private static double[] cumulativeDistances(List<Wgs84Coordinate> geometry) {
        double[] cumulative = new double[geometry.size()];
        for (int index = 1; index < geometry.size(); index++) {
            cumulative[index] = cumulative[index - 1]
                    + GeoDistance.meters(geometry.get(index - 1), geometry.get(index));
        }
        return cumulative;
    }

    private static boolean outerEndpointInGraph(
            HardAvoidingGraphHopper hopper,
            Wgs84Coordinate endpoint,
            EdgeFilter snapFilter) {
        Snap snap = hopper.getLocationIndex().findClosest(
                endpoint.lat(), endpoint.lng(), snapFilter);
        return snap.isValid() && snap.getQueryDistance() < 2000;
    }

    private static void writeReport(
            Path report,
            Configuration configuration,
            GraphHopperManager graphManager,
            List<ScenarioResult> results,
            long elapsedMillis) throws Exception {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 北京图出界点选择 POC 报告\n\n");
        markdown.append("- 生成时间：").append(java.time.Instant.now()).append("\n");
        markdown.append("- PBF：").append(configuration.pbf()).append("\n");
        markdown.append("- 图缓存：").append(configuration.graphCache()).append("\n");
        markdown.append("- 图指纹：").append(graphManager.requireGraphFingerprint()).append("\n");
        markdown.append("- 边界：").append(configuration.boundary()).append("\n");
        markdown.append("- 场景数：").append(results.size())
                .append("，总耗时：").append(elapsedMillis).append(" ms\n\n");
        markdown.append("## 逐场景结果\n\n");
        markdown.append("| 场景 | 方向 | 选中层 | 该层方向候选 | 通行口 | 方位差° | 界内km | 直线km | 界内组装 | 交接点 | 交接点净空m | 界外A*对比 |\n");
        markdown.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ScenarioResult result : results) {
            markdown.append("| ").append(result.scenario().id())
                    .append(" | ").append(result.scenario().direction())
                    .append(" | ").append(result.selectedTier())
                    .append(" | ").append(result.directionFilteredCount())
                    .append(" | ").append(shortId(result.selectedPortalId()))
                    .append(" | ").append(round(result.directionDeltaDegrees()))
                    .append(" | ").append(round(result.insideDistanceMeters() / 1000.0))
                    .append(" | ").append(round(result.straightLineDistanceMeters() / 1000.0))
                    .append(" | ").append(result.insideLegOk() ? "OK" : "FAIL")
                    .append(" | ").append(result.handoffCoordinate() == null
                            ? "无" : result.handoffCoordinate().lng() + ","
                            + result.handoffCoordinate().lat())
                    .append(" | ").append(round(result.handoffClearanceMeters()))
                    .append(" | ").append(result.fullReferenceBestPortalId() == null
                            ? (result.outerEndpointInGraph() ? "A*失败" : "超出图范围")
                            : shortId(result.fullReferenceBestPortalId()))
                    .append(" |\n");
        }
        markdown.append("\n## 详细说明\n\n");
        for (ScenarioResult result : results) {
            markdown.append("### ").append(result.scenario().id()).append("\n\n");
            markdown.append("- 说明：").append(result.scenario().note()).append("\n");
            markdown.append("- 选中通行口：").append(result.selectedPortalId())
                    .append("（").append(blankToDash(result.selectedPortalName()))
                    .append("），坐标 ").append(result.selectedPortalCoordinate() == null
                            ? "无" : result.selectedPortalCoordinate().lng() + ","
                            + result.selectedPortalCoordinate().lat()).append("\n");
            markdown.append("- 界内段：").append(round(result.insideLegDistanceMeters() / 1000.0))
                    .append(" km，").append(result.insideLegPoints())
                    .append(" 点，组装 ")
                    .append(result.insideLegOk() ? "成功" : "失败")
                    .append(blankToDash(result.insideLegError())).append("\n");
            markdown.append("- 高德导航交接点：")
                    .append(result.handoffCoordinate() == null ? "无"
                            : result.handoffCoordinate().lng() + ", "
                            + result.handoffCoordinate().lat())
                    .append("，沿路距通行口 ").append(round(result.handoffDistanceFromCrossingMeters()))
                    .append(" m，边界净空 ").append(round(result.handoffClearanceMeters()))
                    .append(" m，道路：").append(blankToDash(result.handoffRoadName()))
                    .append(result.handoffOk() ? "（界外✓）" : "（界外✗）").append("\n");
            markdown.append("- 界外端点在图内：").append(result.outerEndpointInGraph())
                    .append("；").append(result.fullReferenceNote()).append("\n");
            markdown.append("- 单场景耗时：").append(result.elapsedMillis()).append(" ms\n\n");
        }
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, markdown.toString(), StandardCharsets.UTF_8);
        System.out.println("POC_REPORT=" + report.toAbsolutePath());
        System.out.println(markdown);
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        return id.length() <= 40 ? id : id.substring(0, 40) + "…";
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static Configuration configuration() {
        String pbf = System.getProperty("real.pbf");
        String graphCache = System.getProperty("real.graph.cache");
        String cameraJson = System.getProperty("real.camera.json");
        String boundary = System.getProperty("sixth.ring.boundary.input");
        String report = System.getProperty("sixth.ring.exit.report.output");
        if (pbf == null || pbf.isBlank()
                || graphCache == null || graphCache.isBlank()
                || cameraJson == null || cameraJson.isBlank()
                || boundary == null || boundary.isBlank()
                || report == null || report.isBlank()) {
            return null;
        }
        return new Configuration(
                Path.of(pbf).toAbsolutePath().normalize(),
                Path.of(graphCache).toAbsolutePath().normalize(),
                Path.of(cameraJson).toAbsolutePath().normalize(),
                Path.of(boundary).toAbsolutePath().normalize(),
                Path.of(report).toAbsolutePath().normalize());
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
                        configuration.graphCache().resolve("exit-point-poc-snapshots").toString(),
                        true,
                        10_000,
                        updateProperties()),
                new AppProperties.Admin(true));
    }

    private record Scenario(
            String id,
            SixthRingPortal.Direction direction,
            Wgs84Coordinate inside,
            Wgs84Coordinate outside,
            String note) {
    }

    private record ScoredCandidate(
            EdgeKeyMultiTargetDijkstra.PortalPath portalPath,
            ControlReleasePoint releasePoint,
            double score,
            double directionDeltaDegrees) {
    }

    private record FullReferenceOption(
            String portalId,
            double distanceMeters,
            long durationMillis) {
    }

    private record HandoffPoint(
            Wgs84Coordinate coordinate,
            String roadName,
            double distanceFromCrossingMeters,
            double clearanceMeters) {
    }

    private record Configuration(
            Path pbf,
            Path graphCache,
            Path cameraJson,
            Path boundary,
            Path report) {
    }

    private record ScenarioResult(
            Scenario scenario,
            String selectedPortalId,
            String selectedPortalName,
            Wgs84Coordinate selectedPortalCoordinate,
            int selectedTier,
            int directionFilteredCount,
            double directionDeltaDegrees,
            double insideDistanceMeters,
            double straightLineDistanceMeters,
            boolean insideLegOk,
            String insideLegError,
            double insideLegDistanceMeters,
            int insideLegPoints,
            Wgs84Coordinate handoffCoordinate,
            String handoffRoadName,
            double handoffDistanceFromCrossingMeters,
            double handoffClearanceMeters,
            boolean handoffOk,
            boolean outerEndpointInGraph,
            String fullReferenceBestPortalId,
            double fullReferenceBestDistanceMeters,
            long fullReferenceBestDurationMillis,
            int fullReferenceEvaluatedCount,
            String fullReferenceNote,
            long elapsedMillis,
            String failure) {

        static Builder builder(Scenario scenario) {
            return new Builder(scenario);
        }

        static final class Builder {
            private final Scenario scenario;
            private String selectedPortalId = "";
            private String selectedPortalName = "";
            private Wgs84Coordinate selectedPortalCoordinate;
            private int selectedTier;
            private int directionFilteredCount;
            private double directionDeltaDegrees;
            private double insideDistanceMeters;
            private double straightLineDistanceMeters;
            private boolean insideLegOk;
            private String insideLegError = "";
            private double insideLegDistanceMeters;
            private int insideLegPoints;
            private Wgs84Coordinate handoffCoordinate;
            private String handoffRoadName = "";
            private double handoffDistanceFromCrossingMeters;
            private double handoffClearanceMeters = -1;
            private boolean handoffOk;
            private boolean outerEndpointInGraph;
            private String fullReferenceBestPortalId;
            private double fullReferenceBestDistanceMeters;
            private long fullReferenceBestDurationMillis;
            private int fullReferenceEvaluatedCount;
            private String fullReferenceNote = "";
            private long elapsedMillis;
            private String failure = "";

            private Builder(Scenario scenario) {
                this.scenario = scenario;
            }

            Builder selectedPortalId(String value) {
                this.selectedPortalId = value;
                return this;
            }

            Builder selectedPortalName(String value) {
                this.selectedPortalName = value == null ? "" : value;
                return this;
            }

            Builder selectedPortalCoordinate(Wgs84Coordinate value) {
                this.selectedPortalCoordinate = value;
                return this;
            }

            Builder selectedTier(int value) {
                this.selectedTier = value;
                return this;
            }

            Builder directionFilteredCount(int value) {
                this.directionFilteredCount = value;
                return this;
            }

            Builder directionDeltaDegrees(double value) {
                this.directionDeltaDegrees = value;
                return this;
            }

            Builder insideDistanceMeters(double value) {
                this.insideDistanceMeters = value;
                return this;
            }

            Builder straightLineDistanceMeters(double value) {
                this.straightLineDistanceMeters = value;
                return this;
            }

            Builder insideLegOk(boolean value) {
                this.insideLegOk = value;
                return this;
            }

            Builder insideLegError(String value) {
                this.insideLegError = value == null ? "" : value;
                return this;
            }

            Builder insideLegDistanceMeters(double value) {
                this.insideLegDistanceMeters = value;
                return this;
            }

            Builder insideLegPoints(int value) {
                this.insideLegPoints = value;
                return this;
            }

            Builder handoffCoordinate(Wgs84Coordinate value) {
                this.handoffCoordinate = value;
                return this;
            }

            Builder handoffRoadName(String value) {
                this.handoffRoadName = value == null ? "" : value;
                return this;
            }

            Builder handoffDistanceFromCrossingMeters(double value) {
                this.handoffDistanceFromCrossingMeters = value;
                return this;
            }

            Builder handoffClearanceMeters(double value) {
                this.handoffClearanceMeters = value;
                return this;
            }

            Builder handoffOk(boolean value) {
                this.handoffOk = value;
                return this;
            }

            Builder outerEndpointInGraph(boolean value) {
                this.outerEndpointInGraph = value;
                return this;
            }

            Builder fullReferenceBestPortalId(String value) {
                this.fullReferenceBestPortalId = value;
                return this;
            }

            Builder fullReferenceBestDistanceMeters(double value) {
                this.fullReferenceBestDistanceMeters = value;
                return this;
            }

            Builder fullReferenceBestDurationMillis(long value) {
                this.fullReferenceBestDurationMillis = value;
                return this;
            }

            Builder fullReferenceEvaluatedCount(int value) {
                this.fullReferenceEvaluatedCount = value;
                return this;
            }

            Builder fullReferenceNote(String value) {
                this.fullReferenceNote = value == null ? "" : value;
                return this;
            }

            Builder elapsedMillis(long value) {
                this.elapsedMillis = value;
                return this;
            }

            Builder failure(String value) {
                this.failure = value;
                return this;
            }

            ScenarioResult build() {
                return new ScenarioResult(
                        scenario,
                        selectedPortalId,
                        selectedPortalName,
                        selectedPortalCoordinate,
                        selectedTier,
                        directionFilteredCount,
                        directionDeltaDegrees,
                        insideDistanceMeters,
                        straightLineDistanceMeters,
                        insideLegOk,
                        insideLegError,
                        insideLegDistanceMeters,
                        insideLegPoints,
                        handoffCoordinate,
                        handoffRoadName,
                        handoffDistanceFromCrossingMeters,
                        handoffClearanceMeters,
                        handoffOk,
                        outerEndpointInGraph,
                        fullReferenceBestPortalId,
                        fullReferenceBestDistanceMeters,
                        fullReferenceBestDurationMillis,
                        fullReferenceEvaluatedCount,
                        fullReferenceNote,
                        elapsedMillis,
                        failure);
            }
        }
    }
}
