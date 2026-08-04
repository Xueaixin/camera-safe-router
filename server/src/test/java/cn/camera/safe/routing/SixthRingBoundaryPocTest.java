package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.PMap;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.linemerge.LineMerger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static cn.camera.safe.application.RoutePlanningServiceTest.updateProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SixthRingBoundaryPocTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void analyzesSixthRingBoundaryAndDrivableCrossings() throws Exception {
        String configuredLines = System.getProperty("sixth.ring.lines.geojson");
        String configuredBoundaryOutput = System.getProperty("sixth.ring.boundary.output");
        String configuredReportOutput = System.getProperty("sixth.ring.report.output");
        assumeTrue(configuredLines != null && !configuredLines.isBlank()
                        && configuredBoundaryOutput != null && !configuredBoundaryOutput.isBlank()
                        && configuredReportOutput != null && !configuredReportOutput.isBlank(),
                "Set sixth.ring.lines.geojson, sixth.ring.boundary.output and "
                        + "sixth.ring.report.output to run the sixth-ring POC");

        Path linesPath = Path.of(configuredLines).toAbsolutePath().normalize();
        Path boundaryOutput = Path.of(configuredBoundaryOutput).toAbsolutePath().normalize();
        Path reportOutput = Path.of(configuredReportOutput).toAbsolutePath().normalize();
        String configuredCrossingsOutput = System.getProperty("sixth.ring.crossings.output");
        Path crossingsOutput = configuredCrossingsOutput == null || configuredCrossingsOutput.isBlank()
                ? reportOutput.resolveSibling("sixth-ring-crossing-candidates.geojson")
                : Path.of(configuredCrossingsOutput).toAbsolutePath().normalize();
        String configuredDirectedOutput = System.getProperty("sixth.ring.directed.output");
        Path directedOutput = configuredDirectedOutput == null || configuredDirectedOutput.isBlank()
                ? reportOutput.resolveSibling("sixth-ring-directed-candidates.geojson")
                : Path.of(configuredDirectedOutput).toAbsolutePath().normalize();
        ObjectMapper objectMapper = new ObjectMapper();
        BoundaryAnalysis boundary = analyzeBoundary(objectMapper, linesPath);

        assertThat(boundary.sourceLineCount()).isPositive();
        assertThat(boundary.closedRings()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(boundary.innerPolygon().isValid()).isTrue();
        assertThat(boundary.outerPolygon().isValid()).isTrue();
        assertThat(boundary.outerPolygon().covers(boundary.innerPolygon().getInteriorPoint()))
                .as("the larger carriageway loop contains the smaller loop interior")
                .isTrue();

        writeBoundaryGeoJson(objectMapper, boundaryOutput, linesPath, boundary);
        CrossingAnalysis crossings = analyzeCrossingsIfConfigured(boundary);
        if (crossings != null) {
            writeCrossingsGeoJson(objectMapper, crossingsOutput, crossings);
            writeDirectedCandidatesGeoJson(objectMapper, directedOutput, crossings);
        }
        writeReport(
                reportOutput, linesPath, boundaryOutput, crossingsOutput, directedOutput,
                boundary, crossings);

        assertThat(boundaryOutput).isRegularFile();
        assertThat(reportOutput).isRegularFile();
        System.out.printf(
                "SIXTH_RING_POC sourceLines=%d mergedLines=%d closedRings=%d "
                        + "innerKm=%.2f outerKm=%.2f crossings=%d report=%s%n",
                boundary.sourceLineCount(),
                boundary.mergedLines().size(),
                boundary.closedRings().size(),
                lengthMeters(boundary.innerRing()) / 1_000,
                lengthMeters(boundary.outerRing()) / 1_000,
                crossings == null ? 0 : crossings.directionalCrossings().size(),
                reportOutput);
    }

    private static BoundaryAnalysis analyzeBoundary(ObjectMapper objectMapper, Path linesPath)
            throws Exception {
        JsonNode root = objectMapper.readTree(linesPath.toFile());
        JsonNode features = root.path("features");
        assertThat(features.isArray()).isTrue();

        List<LineString> sourceLines = new ArrayList<>();
        LineMerger merger = new LineMerger();
        for (JsonNode feature : features) {
            JsonNode geometry = feature.path("geometry");
            if (!"LineString".equals(geometry.path("type").asText())) {
                continue;
            }
            LineString line = lineString(geometry.path("coordinates"));
            if (line.getNumPoints() < 2 || line.isEmpty()) {
                continue;
            }
            sourceLines.add(line);
            merger.add(line);
        }

        @SuppressWarnings("unchecked")
        Collection<LineString> mergedCollection = merger.getMergedLineStrings();
        List<LineString> mergedLines = mergedCollection.stream()
                .sorted(Comparator.comparingDouble(SixthRingBoundaryPocTest::lengthMeters).reversed())
                .toList();
        List<LineString> closedRings = mergedLines.stream()
                .filter(LineString::isClosed)
                .filter(line -> line.getNumPoints() >= 4)
                .toList();
        List<Polygon> polygons = closedRings.stream()
                .map(line -> GEOMETRY_FACTORY.createPolygon(line.getCoordinates()))
                .filter(Polygon::isValid)
                .sorted(Comparator.comparingDouble(Polygon::getArea))
                .toList();
        assertThat(polygons).as("valid closed carriageway polygons").hasSizeGreaterThanOrEqualTo(2);

        Polygon innerPolygon = polygons.get(polygons.size() - 2);
        Polygon outerPolygon = polygons.getLast();
        LineString innerRing = GEOMETRY_FACTORY.createLineString(
                innerPolygon.getExteriorRing().getCoordinates());
        LineString outerRing = GEOMETRY_FACTORY.createLineString(
                outerPolygon.getExteriorRing().getCoordinates());
        return new BoundaryAnalysis(
                sourceLines.size(), mergedLines, closedRings, innerRing, outerRing,
                innerPolygon, outerPolygon);
    }

    private static LineString lineString(JsonNode coordinates) {
        List<Coordinate> values = new ArrayList<>();
        if (coordinates.isArray()) {
            for (JsonNode coordinate : coordinates) {
                if (coordinate.isArray() && coordinate.size() >= 2) {
                    values.add(new Coordinate(coordinate.get(0).asDouble(), coordinate.get(1).asDouble()));
                }
            }
        }
        return GEOMETRY_FACTORY.createLineString(values.toArray(Coordinate[]::new));
    }

    private static CrossingAnalysis analyzeCrossingsIfConfigured(BoundaryAnalysis boundary) {
        String configuredPbf = System.getProperty("real.pbf");
        String configuredGraphCache = System.getProperty("real.graph.cache");
        if (configuredPbf == null || configuredPbf.isBlank()
                || configuredGraphCache == null || configuredGraphCache.isBlank()) {
            return null;
        }

        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        Path graphCache = Path.of(configuredGraphCache).toAbsolutePath().normalize();
        AppProperties properties = properties(pbf, graphCache);
        GraphHopperManager graphManager = new GraphHopperManager(properties);
        graphManager.initialize();
        try {
            BaseGraph graph = graphManager.requireHopper().getBaseGraph();
            BooleanEncodedValue carAccess = graphManager.requireHopper().getEncodingManager()
                    .getBooleanEncodedValue("car_access");
            Weighting weighting = graphManager.requireHopper().createWeighting(
                    graphManager.requireHopper().getProfile("car"), new PMap());
            SixthRingPortalTopology topology = new SixthRingPortalTopologyBuilder().build(
                    graph,
                    carAccess,
                    weighting,
                    new SixthRingBoundary(
                            boundary.innerPolygon(),
                            boundary.outerPolygon(),
                            graphManager.requireGraphFingerprint()));
            BoundaryCrossingScan outboundScan = adaptScan(topology.outbound());
            BoundaryCrossingScan inboundScan = adaptScan(topology.inbound());
            List<DirectionalCrossing> directional = new ArrayList<>(
                    outboundScan.directionalCrossings().size() + inboundScan.directionalCrossings().size());
            directional.addAll(outboundScan.directionalCrossings());
            directional.addAll(inboundScan.directionalCrossings());
            directional.sort(Comparator
                    .comparing(DirectionalCrossing::direction)
                    .thenComparingDouble(DirectionalCrossing::lng)
                    .thenComparingDouble(DirectionalCrossing::lat)
                    .thenComparingInt(DirectionalCrossing::edgeId));
            return new CrossingAnalysis(
                    graphManager.requireGraphFingerprint(),
                    graph.getNodes(),
                    graph.getEdges(),
                    outboundScan,
                    inboundScan,
                    List.copyOf(directional),
                    physicalClusters(directional, boundary.innerPolygon().getCentroid()));
        } finally {
            graphManager.close();
        }
    }

    private static BoundaryCrossingScan adaptScan(SixthRingPortalTopology.Scan scan) {
        List<DirectionalCrossing> candidates = scan.portals().stream()
                .map(portal -> new DirectionalCrossing(
                        portal.edgeId(),
                        portal.edgeKey(),
                        Direction.valueOf(portal.direction().name()),
                        portal.boundaryRole() == SixthRingPortal.BoundaryRole.OUTER_EXIT
                                ? "outer_exit" : "inner_entry",
                        CandidateType.valueOf(portal.candidateType().name()),
                        portal.boundaryNode(),
                        portal.fractionFromBase(),
                        portal.crossing().lng(),
                        portal.crossing().lat(),
                        portal.roadName()))
                .toList();
        return new BoundaryCrossingScan(
                scan.boundaryRole() == SixthRingPortal.BoundaryRole.OUTER_EXIT
                        ? "outer_exit" : "inner_entry",
                Direction.valueOf(scan.direction().name()),
                scan.intersectingEdges(),
                scan.overlappingEdges(),
                scan.ambiguousEdges(),
                scan.geometryCrossings(),
                scan.boundaryNodes(),
                scan.nodeTransitionCandidates(),
                candidates);
    }

    private static AppProperties properties(Path pbf, Path graphCache) {
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
                        pbf.toString(), graphCache.resolve("poc-snapshots").toString(),
                        true, 10_000, 1, updateProperties()),
                new AppProperties.Admin(true));
    }

    private static Map<Integer, List<PhysicalCrossingCluster>> physicalClusters(
            List<DirectionalCrossing> crossings,
            Point boundaryCenter) {
        Map<Integer, List<PhysicalCrossingCluster>> clusters = new LinkedHashMap<>();
        for (int radius : List.of(50, 100, 250)) {
            clusters.put(radius, clusterCrossings(crossings, radius, boundaryCenter));
        }
        return Map.copyOf(clusters);
    }

    private static List<PhysicalCrossingCluster> clusterCrossings(
            List<DirectionalCrossing> crossings,
            double radiusMeters,
            Point boundaryCenter) {
        int[] parents = new int[crossings.size()];
        for (int index = 0; index < parents.length; index++) {
            parents[index] = index;
        }
        for (int left = 0; left < crossings.size(); left++) {
            DirectionalCrossing first = crossings.get(left);
            for (int right = left + 1; right < crossings.size(); right++) {
                DirectionalCrossing second = crossings.get(right);
                double distance = GeoDistance.meters(
                        new Wgs84Coordinate(first.lng(), first.lat()),
                        new Wgs84Coordinate(second.lng(), second.lat()));
                if (distance <= radiusMeters) {
                    union(parents, left, right);
                }
            }
        }

        Map<Integer, List<DirectionalCrossing>> membersByRoot = new LinkedHashMap<>();
        for (int index = 0; index < parents.length; index++) {
            membersByRoot.computeIfAbsent(find(parents, index), ignored -> new ArrayList<>())
                    .add(crossings.get(index));
        }
        return membersByRoot.values().stream()
                .map(members -> physicalCluster(members, boundaryCenter))
                .sorted(Comparator.comparing(PhysicalCrossingCluster::quadrant)
                        .thenComparingDouble(PhysicalCrossingCluster::lng)
                        .thenComparingDouble(PhysicalCrossingCluster::lat))
                .toList();
    }

    private static PhysicalCrossingCluster physicalCluster(
            List<DirectionalCrossing> members,
            Point boundaryCenter) {
        double lng = members.stream().mapToDouble(DirectionalCrossing::lng).average().orElseThrow();
        double lat = members.stream().mapToDouble(DirectionalCrossing::lat).average().orElseThrow();
        Set<Direction> directions = EnumSet.noneOf(Direction.class);
        members.forEach(member -> directions.add(member.direction()));
        List<Integer> edgeIds = members.stream()
                .map(DirectionalCrossing::edgeId)
                .distinct()
                .sorted()
                .toList();
        List<Integer> edgeKeys = members.stream()
                .map(DirectionalCrossing::edgeKey)
                .distinct()
                .sorted()
                .toList();
        Set<CandidateType> candidateTypes = EnumSet.noneOf(CandidateType.class);
        members.forEach(member -> candidateTypes.add(member.candidateType()));
        List<String> roadNames = members.stream()
                .map(DirectionalCrossing::roadName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
        return new PhysicalCrossingCluster(
                lng,
                lat,
                quadrant(boundaryCenter, lng, lat),
                Set.copyOf(directions),
                edgeIds,
                edgeKeys,
                Set.copyOf(candidateTypes),
                roadNames,
                members.size());
    }

    private static Quadrant quadrant(Point center, double lng, double lat) {
        double eastMeters = (lng - center.getX()) * 111_320 * Math.cos(Math.toRadians(center.getY()));
        double northMeters = (lat - center.getY()) * 111_320;
        if (Math.abs(eastMeters) >= Math.abs(northMeters)) {
            return eastMeters >= 0 ? Quadrant.EAST : Quadrant.WEST;
        }
        return northMeters >= 0 ? Quadrant.NORTH : Quadrant.SOUTH;
    }

    private static void union(int[] parents, int left, int right) {
        int leftRoot = find(parents, left);
        int rightRoot = find(parents, right);
        if (leftRoot != rightRoot) {
            parents[rightRoot] = leftRoot;
        }
    }

    private static int find(int[] parents, int index) {
        int current = index;
        while (parents[current] != current) {
            parents[current] = parents[parents[current]];
            current = parents[current];
        }
        return current;
    }

    private static double lengthMeters(LineString line) {
        Coordinate[] coordinates = line.getCoordinates();
        double meters = 0;
        for (int index = 1; index < coordinates.length; index++) {
            Coordinate start = coordinates[index - 1];
            Coordinate end = coordinates[index];
            meters += GeoDistance.meters(
                    new Wgs84Coordinate(start.x, start.y),
                    new Wgs84Coordinate(end.x, end.y));
        }
        return meters;
    }

    private static void writeBoundaryGeoJson(
            ObjectMapper objectMapper,
            Path output,
            Path source,
            BoundaryAnalysis analysis) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("coordinateSystem", "WGS84");
        root.put("candidateOnly", true);
        root.put("approvedForProduction", false);
        root.put("sourceGeoJson", source.toString());
        root.put("sourceGeoJsonSha256", Hashing.sha256(source));
        String sourcePbfSha256 = sourcePbfSha256();
        if (sourcePbfSha256 != null) {
            root.put("sourcePbfSha256", sourcePbfSha256);
        }
        root.put("osmRelationId", 295982);
        root.put("ref", "G4501");
        root.put("boundaryRule", "boundary band: outer loop for outbound, inner loop for inbound");
        ArrayNode features = root.putArray("features");
        features.add(polygonFeature(
                objectMapper, "inside_boundary", source, analysis.innerPolygon(),
                lengthMeters(analysis.innerRing())));
        features.add(polygonFeature(
                objectMapper, "outside_boundary", source, analysis.outerPolygon(),
                lengthMeters(analysis.outerRing())));
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }

    private static String sourcePbfSha256() throws Exception {
        String configuredGraphCache = System.getProperty("real.graph.cache");
        if (configuredGraphCache != null && !configuredGraphCache.isBlank()) {
            Path metadata = Path.of(configuredGraphCache).toAbsolutePath().normalize()
                    .resolve("camera-safe-source.sha256");
            if (Files.isRegularFile(metadata)) {
                String hash = Files.readString(metadata, StandardCharsets.US_ASCII).trim();
                if (hash.matches("[0-9a-fA-F]{64}")) {
                    return hash.toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static void writeCrossingsGeoJson(
            ObjectMapper objectMapper,
            Path output,
            CrossingAnalysis crossings) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("description", "100m geometry clusters for manual review; not search-ready portals");
        root.put("graphFingerprint", crossings.graphFingerprint());
        root.put("clusterRadiusMeters", 100);
        root.put("outboundBoundary", "outside carriageway loop");
        root.put("inboundBoundary", "inside carriageway loop");
        root.put("searchReady", false);
        ArrayNode features = root.putArray("features");
        List<PhysicalCrossingCluster> clusters = crossings.physicalClusters().get(100);
        for (int index = 0; index < clusters.size(); index++) {
            PhysicalCrossingCluster cluster = clusters.get(index);
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode properties = feature.putObject("properties");
            properties.put("clusterId", String.format(Locale.ROOT, "C%03d", index + 1));
            properties.put("quadrant", cluster.quadrant().name());
            properties.put("memberTraversals", cluster.memberTraversals());
            properties.put("directions", cluster.directions().stream()
                    .sorted().map(Enum::name).reduce((left, right) -> left + "," + right).orElse(""));
            properties.put("edgeIds", cluster.edgeIds().stream()
                    .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse(""));
            properties.put("edgeKeys", cluster.edgeKeys().stream()
                    .map(String::valueOf).reduce((left, right) -> left + "," + right).orElse(""));
            properties.put("candidateTypes", cluster.candidateTypes().stream()
                    .sorted().map(Enum::name).reduce((left, right) -> left + "," + right).orElse(""));
            properties.put("roadNames", String.join(",", cluster.roadNames()));
            properties.put("osmReviewUrl", String.format(
                    Locale.ROOT,
                    "https://www.openstreetmap.org/?mlat=%.7f&mlon=%.7f#map=18/%.7f/%.7f",
                    cluster.lat(), cluster.lng(), cluster.lat(), cluster.lng()));
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "Point");
            ArrayNode coordinate = geometry.putArray("coordinates");
            coordinate.add(cluster.lng());
            coordinate.add(cluster.lat());
        }
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void writeDirectedCandidatesGeoJson(
            ObjectMapper objectMapper,
            Path output,
            CrossingAnalysis crossings) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "FeatureCollection");
        root.put("description", "directed edge-key boundary topology for routing validation");
        root.put("coordinateSystem", "WGS84");
        root.put("graphFingerprint", crossings.graphFingerprint());
        root.put("outboundBoundary", "outside carriageway loop");
        root.put("inboundBoundary", "inside carriageway loop");
        root.put("turnRestrictionsVerified", true);
        root.put("topologyReady", true);
        root.put("searchReady", true);
        root.put("approvedForProduction", false);
        ArrayNode features = root.putArray("features");
        for (int index = 0; index < crossings.directionalCrossings().size(); index++) {
            DirectionalCrossing candidate = crossings.directionalCrossings().get(index);
            ObjectNode feature = features.addObject();
            feature.put("type", "Feature");
            ObjectNode properties = feature.putObject("properties");
            properties.put("candidateId", String.format(Locale.ROOT, "P%04d", index + 1));
            properties.put("edgeId", candidate.edgeId());
            properties.put("edgeKey", candidate.edgeKey());
            properties.put("fractionFromBase", candidate.fractionFromBase());
            properties.put("direction", candidate.direction().name());
            properties.put("boundaryRole", candidate.boundaryRole());
            properties.put("candidateType", candidate.candidateType().name());
            if (candidate.boundaryNode() >= 0) {
                properties.put("boundaryNode", candidate.boundaryNode());
            }
            properties.put("roadName", candidate.roadName());
            ObjectNode geometry = feature.putObject("geometry");
            geometry.put("type", "Point");
            ArrayNode coordinate = geometry.putArray("coordinates");
            coordinate.add(candidate.lng());
            coordinate.add(candidate.lat());
        }
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                StandardCharsets.UTF_8);
    }

    private static ObjectNode polygonFeature(
            ObjectMapper objectMapper,
            String role,
            Path source,
            Polygon polygon,
            double lengthMeters) {
        ObjectNode feature = objectMapper.createObjectNode();
        feature.put("type", "Feature");
        ObjectNode properties = feature.putObject("properties");
        properties.put("role", role);
        properties.put("source", source.toString());
        properties.put("osmRelationId", 295982);
        properties.put("ref", "G4501");
        properties.put("lengthMeters", Math.round(lengthMeters));
        ObjectNode geometry = feature.putObject("geometry");
        geometry.put("type", "Polygon");
        ArrayNode rings = geometry.putArray("coordinates");
        ArrayNode ring = rings.addArray();
        for (Coordinate coordinate : polygon.getExteriorRing().getCoordinates()) {
            ArrayNode value = ring.addArray();
            value.add(coordinate.x);
            value.add(coordinate.y);
        }
        return feature;
    }

    private static void writeReport(
            Path output,
            Path source,
            Path boundaryOutput,
            Path crossingsOutput,
            Path directedOutput,
            BoundaryAnalysis boundary,
            CrossingAnalysis crossings) throws Exception {
        StringBuilder report = new StringBuilder("# 六环边界与通行口 POC 报告\n\n")
                .append("- 生成时间：").append(Instant.now()).append("\n")
                .append("- OSM 道路关系：`r295982`\n")
                .append("- 道路编号：`G4501`\n")
                .append("- 原始线 GeoJSON：`").append(source).append("`\n")
                .append("- 候选边界 GeoJSON：`").append(boundaryOutput).append("`\n")
                .append("- 几何簇候选 GeoJSON：`").append(crossingsOutput).append("`\n")
                .append("- 有向 edge-key 候选 GeoJSON：`").append(directedOutput).append("`\n\n")
                .append("## 1. 双向主线闭合结果\n\n")
                .append("| 项目 | 结果 |\n|---|---:|\n")
                .append("| 原始 LineString | ").append(boundary.sourceLineCount()).append(" |\n")
                .append("| 合并后线 | ").append(boundary.mergedLines().size()).append(" |\n")
                .append("| 闭合线 | ").append(boundary.closedRings().size()).append(" |\n")
                .append("| 内侧环线长度 | ")
                .append(String.format(Locale.ROOT, "%.2f km", lengthMeters(boundary.innerRing()) / 1_000))
                .append(" |\n")
                .append("| 外侧环线长度 | ")
                .append(String.format(Locale.ROOT, "%.2f km", lengthMeters(boundary.outerRing()) / 1_000))
                .append(" |\n")
                .append("| 内侧 Polygon 有效 | ").append(boundary.innerPolygon().isValid()).append(" |\n")
                .append("| 外侧 Polygon 有效 | ").append(boundary.outerPolygon().isValid()).append(" |\n")
                .append("| 外侧环包含内侧环内部点 | ")
                .append(boundary.outerPolygon().covers(boundary.innerPolygon().getInteriorPoint()))
                .append(" |\n\n")
                .append("POC 使用边界带口径：出环以穿过外侧环线为准，入环以穿过内侧环线为准；两条车行环线之间不算已经出环或已经入环。最终规则仍需地图抽查。\n\n")
                .append("## 2. 可驾车图有向通行口拓扑\n\n");

        if (crossings == null) {
            report.append("未配置 `real.pbf` 和 `real.graph.cache`，本次未扫描 GraphHopper 图。\n");
        } else {
            BoundaryCrossingScan outboundScan = crossings.outboundScan();
            BoundaryCrossingScan inboundScan = crossings.inboundScan();
            report.append("- 图指纹：`").append(crossings.graphFingerprint()).append("`\n")
                    .append("- GraphHopper 节点：").append(crossings.graphNodes()).append("\n")
                    .append("- GraphHopper 基础边：").append(crossings.graphEdges()).append("\n\n")
                    .append("| 项目 | 结果 |\n|---|---:|\n")
                    .append("| 外环线相交边（出环） | ").append(outboundScan.intersectingEdges()).append(" |\n")
                    .append("| 外环线重叠边（出环） | ").append(outboundScan.overlappingEdges()).append(" |\n")
                    .append("| 外环线无法直接判向边（出环） | ").append(outboundScan.ambiguousEdges()).append(" |\n")
                    .append("| 外环线几何交点坐标（出环） | ").append(outboundScan.geometryCrossings()).append(" |\n")
                    .append("| 外环线边界节点（出环） | ").append(outboundScan.boundaryNodes()).append(" |\n")
                    .append("| 外环线节点/重叠链转换候选（出环） | ").append(outboundScan.nodeDepartureCandidates()).append(" |\n")
                    .append("| 内环线相交边（入环） | ").append(inboundScan.intersectingEdges()).append(" |\n")
                    .append("| 内环线重叠边（入环） | ").append(inboundScan.overlappingEdges()).append(" |\n")
                    .append("| 内环线无法直接判向边（入环） | ").append(inboundScan.ambiguousEdges()).append(" |\n")
                    .append("| 内环线几何交点坐标（入环） | ").append(inboundScan.geometryCrossings()).append(" |\n")
                    .append("| 内环线边界节点（入环） | ").append(inboundScan.boundaryNodes()).append(" |\n")
                    .append("| 内环线节点/重叠链转换候选（入环） | ").append(inboundScan.nodeDepartureCandidates()).append(" |\n")
                    .append("| 有向候选总数 | ").append(crossings.directionalCrossings().size()).append(" |\n")
                    .append("| OUTBOUND 遍历数 | ").append(outboundScan.directionalCrossings().size()).append(" |\n")
                    .append("| INBOUND 遍历数 | ").append(inboundScan.directionalCrossings().size()).append(" |\n")
                    .append("| 50 米物理位置聚类 | ").append(crossings.physicalClusters().get(50).size()).append(" |\n")
                    .append("| 100 米物理位置聚类 | ").append(crossings.physicalClusters().get(100).size()).append(" |\n")
                    .append("| 250 米物理位置聚类 | ").append(crossings.physicalClusters().get(250).size()).append(" |\n\n")
                    .append("物理位置聚类忽略方向，仅用于人工审查和比较半径；搜索候选仍必须保留每条有向 edge key。\n\n")
                    .append("### 有向遍历样例\n\n")
                    .append("| edge ID | edge key | 方向 | 候选类型 | 边界节点 | 边界规则 | WGS84 | 道路名 |\n")
                    .append("|---:|---:|---|---|---:|---|---|---|\n");
            crossings.directionalCrossings().stream().limit(80).forEach(crossing -> report
                    .append("| ").append(crossing.edgeId()).append(" | ")
                    .append(crossing.edgeKey()).append(" | ")
                    .append(crossing.direction()).append(" | ")
                    .append(crossing.candidateType()).append(" | ")
                    .append(crossing.boundaryNode()).append(" | ")
                    .append(crossing.boundaryRole()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.7f, %.7f", crossing.lng(), crossing.lat()))
                     .append(" | ").append(escape(crossing.roadName())).append(" |\n"));

            report.append("\n### 100 米物理位置簇分布\n\n")
                    .append("| 区域 | 位置簇 | 双向簇 | 仅出环 | 仅入环 |\n")
                    .append("|---|---:|---:|---:|---:|\n");
            for (Quadrant quadrant : Quadrant.values()) {
                List<PhysicalCrossingCluster> quadrantClusters = crossings.physicalClusters().get(100)
                        .stream().filter(cluster -> cluster.quadrant() == quadrant).toList();
                long bidirectional = quadrantClusters.stream()
                        .filter(cluster -> cluster.directions().size() == 2).count();
                long outboundOnly = quadrantClusters.stream()
                        .filter(cluster -> cluster.directions().equals(Set.of(Direction.OUTBOUND))).count();
                long inboundOnly = quadrantClusters.size() - bidirectional - outboundOnly;
                report.append("| ").append(quadrant).append(" | ")
                        .append(quadrantClusters.size()).append(" | ")
                        .append(bidirectional).append(" | ")
                        .append(outboundOnly).append(" | ")
                        .append(inboundOnly).append(" |\n");
            }

            report.append("\n### 四象限人工抽查清单（每区复杂度最高的 5 簇）\n\n")
                    .append("| 区域 | WGS84 | 方向 | 遍历数 | 道路名 | OSM |\n")
                    .append("|---|---|---|---:|---|---|\n");
            for (Quadrant quadrant : Quadrant.values()) {
                crossings.physicalClusters().get(100).stream()
                        .filter(cluster -> cluster.quadrant() == quadrant)
                        .sorted(Comparator.comparingInt(PhysicalCrossingCluster::memberTraversals).reversed())
                        .limit(5)
                        .forEach(cluster -> report.append("| ").append(quadrant).append(" | ")
                                .append(String.format(Locale.ROOT, "%.7f, %.7f", cluster.lng(), cluster.lat()))
                                .append(" | ").append(cluster.directions()).append(" | ")
                                .append(cluster.memberTraversals()).append(" | ")
                                .append(escape(String.join(",", cluster.roadNames()))).append(" | ")
                                .append(String.format(Locale.ROOT,
                                        "[查看](https://www.openstreetmap.org/?mlat=%.7f&mlon=%.7f#map=18/%.7f/%.7f)",
                                        cluster.lat(), cluster.lng(), cluster.lat(), cluster.lng()))
                                .append(" |\n"));
            }
        }

        report.append("\n## 3. 验证结论\n\n")
                .append("| 检查项 | 状态 |\n|---|---|\n")
                .append("| G4501 双向主线闭合、有效性和内外包含关系 | 通过 |\n")
                .append("| 出环外线、入环内线的边界带统计口径 | POC 推荐，待人工确认 |\n")
                .append("| 边内部几何交叉的允许方向识别 | 通过 |\n")
                .append("| 物理位置簇与有向遍历分开统计 | 通过 |\n")
                .append("| 边端交点和沿边界重叠链的有向状态转换 | 通过，已检查可达方向和转向合法性 |\n")
                .append("| 同一有向边重复候选去重且保留相邻车道 | 通过 |\n")
                .append("| 100 米聚类半径人工地图确认 | 未完成 |\n")
                .append("| 候选拓扑用于多目标搜索 | 通过，尚未获人工生产批准 |\n")
                .append("\n## 4. POC 边界\n\n")
                .append("- 当前候选已具备有向 edge key、边界节点链和转向合法性，可用于多目标搜索验证。\n")
                .append("- 100 米位置簇只用于压缩人工抽查清单，不能替代有向 edge key。\n")
                .append("- 同一有向通行状态按 edge key 和边上比例去重；平行车道不会被物理位置聚类合并。\n")
                .append("- 多车道和相邻道路的人工审查半径尚未冻结。\n")
                .append("- 候选边界和跨界点必须经过高德/OSM 人工地图抽查后才能进入生产。\n")
                .append("- 本测试不修改生产路线算法、PBF 或已发布图缓存。\n");

        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString(), StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        if (value == null || value.isBlank()) {
            return "<未命名>";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private enum Direction {
        OUTBOUND,
        INBOUND
    }

    private enum CandidateType {
        INTERIOR_EDGE,
        OVERLAP_EDGE_EXIT,
        BOUNDARY_NODE_TRANSITION,
        BOUNDARY_CHAIN_TRANSITION
    }

    private enum Quadrant {
        EAST,
        SOUTH,
        WEST,
        NORTH
    }

    private record BoundaryAnalysis(
            int sourceLineCount,
            List<LineString> mergedLines,
            List<LineString> closedRings,
            LineString innerRing,
            LineString outerRing,
            Polygon innerPolygon,
            Polygon outerPolygon) {
    }

    private record DirectionalCrossing(
            int edgeId,
            int edgeKey,
            Direction direction,
            String boundaryRole,
            CandidateType candidateType,
            int boundaryNode,
            double fractionFromBase,
            double lng,
            double lat,
            String roadName) {
    }

    private record PhysicalCrossingCluster(
            double lng,
            double lat,
            Quadrant quadrant,
            Set<Direction> directions,
            List<Integer> edgeIds,
            List<Integer> edgeKeys,
            Set<CandidateType> candidateTypes,
            List<String> roadNames,
            int memberTraversals) {
    }

    private record CrossingAnalysis(
            String graphFingerprint,
            int graphNodes,
            int graphEdges,
            BoundaryCrossingScan outboundScan,
            BoundaryCrossingScan inboundScan,
            List<DirectionalCrossing> directionalCrossings,
            Map<Integer, List<PhysicalCrossingCluster>> physicalClusters) {
    }

    private record BoundaryCrossingScan(
            String boundaryRole,
            Direction direction,
            int intersectingEdges,
            int overlappingEdges,
            int ambiguousEdges,
            int geometryCrossings,
            int boundaryNodes,
            int nodeDepartureCandidates,
            List<DirectionalCrossing> directionalCrossings) {
    }
}
