package cn.camera.safe.routing;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.Profile;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.details.PathDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.graphhopper.util.Parameters.Algorithms.ASTAR_BI;
import static com.graphhopper.util.Parameters.Details.EDGE_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealPbfHardAvoidancePocTest {
    private static final double START_LAT = 39.9087;
    private static final double START_LON = 116.3975;
    private static final double END_LAT = 39.9920;
    private static final double END_LON = 116.4700;

    @TempDir
    Path temporaryDirectory;

    @Test
    void importsCachesRoutesAndRejectsABaselineEdgeInsideTheSearch() throws Exception {
        String configuredPbf = System.getProperty("poc.pbf");
        assumeTrue(configuredPbf != null && !configuredPbf.isBlank(),
                "Set -Dpoc.pbf=<path-to-jingjinji-latest.osm.pbf> to run the real PBF POC");
        Path pbf = Path.of(configuredPbf).toAbsolutePath().normalize();
        assertThat(pbf).isRegularFile();

        Path graphCache = temporaryDirectory.resolve("graph-cache");
        Instant importStarted = Instant.now();
        HardAvoidingGraphHopper hopper = createHopper(graphCache, pbf);
        hopper.importOrLoad();
        long importMillis = Duration.between(importStarted, Instant.now()).toMillis();

        GHRequest request = request(START_LAT, START_LON, END_LAT, END_LON);
        SearchAudit baselineAudit = new SearchAudit();
        GHResponse baseline = hopper.route(request, BlockedEdgeSnapshot.empty(), baselineAudit);
        assertThat(baseline.hasErrors()).as(() -> baseline.getErrors().toString()).isFalse();
        List<Integer> baselineKeys = edgeKeys(baseline.getBest());
        assertThat(baselineKeys).hasSizeGreaterThan(4);

        Attempt successfulAttempt = findReroute(hopper, request, baselineKeys);
        assertThat(successfulAttempt).as("At least one interior baseline edge must be hard-blockable").isNotNull();
        assertThat(successfulAttempt.audit().edgeChecks()).isPositive();
        assertThat(successfulAttempt.audit().blockedRejections()).isPositive();
        assertThat(edgeIds(successfulAttempt.edgeKeys())).doesNotContain(successfulAttempt.blockedEdgeId());

        hopper.close();

        Instant cacheLoadStarted = Instant.now();
        HardAvoidingGraphHopper cachedHopper = createHopper(graphCache, null);
        assertThat(cachedHopper.load()).isTrue();
        long cacheLoadMillis = Duration.between(cacheLoadStarted, Instant.now()).toMillis();
        GHResponse cachedRoute = cachedHopper.route(request, BlockedEdgeSnapshot.empty(), new SearchAudit());
        assertThat(cachedRoute.hasErrors()).as(() -> cachedRoute.getErrors().toString()).isFalse();
        cachedHopper.close();

        System.out.printf(
                "B1_REAL_PBF pbfSha256=%s importMillis=%d cacheLoadMillis=%d baselineEdges=%d blockedEdgeId=%d "
                        + "searchChecks=%d blockedRejections=%d rerouteEdges=%d%n",
                sha256(pbf),
                importMillis,
                cacheLoadMillis,
                baselineKeys.size(),
                successfulAttempt.blockedEdgeId(),
                successfulAttempt.audit().edgeChecks(),
                successfulAttempt.audit().blockedRejections(),
                successfulAttempt.edgeKeys().size());
    }

    private static Attempt findReroute(
            HardAvoidingGraphHopper hopper,
            GHRequest request,
            List<Integer> baselineKeys) {
        List<Integer> candidates = interiorDistinctEdgeIds(baselineKeys);
        for (int edgeId : candidates) {
            SearchAudit audit = new SearchAudit();
            BlockedEdgeSnapshot snapshot = BlockedEdgeSnapshot.blockBothDirections(Set.of(edgeId), "poc-edge-" + edgeId);
            GHResponse response = hopper.route(request, snapshot, audit);
            if (response.hasErrors()) {
                continue;
            }
            List<Integer> rerouteKeys = edgeKeys(response.getBest());
            if (!edgeIds(rerouteKeys).contains(edgeId) && audit.blockedRejections() > 0) {
                return new Attempt(edgeId, rerouteKeys, audit);
            }
        }
        return null;
    }

    private static List<Integer> interiorDistinctEdgeIds(List<Integer> edgeKeys) {
        int margin = Math.max(1, edgeKeys.size() / 5);
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int index = margin; index < edgeKeys.size() - margin; index++) {
            ids.add(GHUtility.getEdgeFromEdgeKey(edgeKeys.get(index)));
        }
        List<Integer> all = new ArrayList<>(ids);
        if (all.size() <= 12) {
            return all;
        }
        List<Integer> sampled = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            sampled.add(all.get(index * (all.size() - 1) / 11));
        }
        return sampled;
    }

    private static Set<Integer> edgeIds(List<Integer> edgeKeys) {
        Set<Integer> edgeIds = new LinkedHashSet<>();
        edgeKeys.forEach(edgeKey -> edgeIds.add(GHUtility.getEdgeFromEdgeKey(edgeKey)));
        return edgeIds;
    }

    private static List<Integer> edgeKeys(ResponsePath path) {
        List<PathDetail> details = path.getPathDetails().get(EDGE_KEY);
        assertThat(details).isNotNull();
        return details.stream().map(detail -> (Integer) detail.getValue()).toList();
    }

    private static GHRequest request(double fromLat, double fromLon, double toLat, double toLon) {
        return new GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile("car")
                .setAlgorithm(ASTAR_BI)
                .setPathDetails(List.of(EDGE_KEY));
    }

    private static HardAvoidingGraphHopper createHopper(Path graphCache, Path pbf) {
        HardAvoidingGraphHopper hopper = new HardAvoidingGraphHopper();
        hopper.setGraphHopperLocation(graphCache.toString());
        if (pbf != null) {
            hopper.setOSMFile(pbf.toString());
        }
        hopper.setEncodedValuesString("car_access|block_private=false,car_average_speed,road_access");
        hopper.setProfiles(new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        hopper.getCHPreparationHandler().setCHProfiles();
        hopper.getLMPreparationHandler().setLMProfiles();
        return hopper;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Attempt(int blockedEdgeId, List<Integer> edgeKeys, SearchAudit audit) {
    }
}
