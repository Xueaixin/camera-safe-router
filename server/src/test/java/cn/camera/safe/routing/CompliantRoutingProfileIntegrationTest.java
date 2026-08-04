package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static com.graphhopper.util.Parameters.Algorithms.ASTAR_BI;
import static com.graphhopper.util.Parameters.Details.EDGE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

class CompliantRoutingProfileIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void routesByDistanceWithoutBreakingLegalityAccessTimeOrCameraBlocking() throws Exception {
        Path osm = temporaryDirectory.resolve("routing-foundation.osm");
        Files.writeString(osm, osmFixture(), StandardCharsets.UTF_8);
        Path currentCache = temporaryDirectory.resolve("current-cache");
        Path candidateCache = temporaryDirectory.resolve("candidate-cache");
        RoutingGraphConfiguration configuration = RoutingGraphConfiguration.resolve(
                routing(osm, currentCache, candidateCache));

        HardAvoidingGraphHopper hopper = configuration.createHopper();
        hopper.setMinNetworkSize(0);
        hopper.setGraphHopperLocation(candidateCache.toString());
        hopper.setOSMFile(osm.toString());
        hopper.importOrLoad();
        try {
            RouteResult distanceFirst = route(
                    hopper, 39.000000, 116.000000, 39.000000, 116.002000,
                    BlockedEdgeSnapshot.empty());
            assertThat(distanceFirst.path().getDistance()).isLessThan(220);
            assertThat(distanceFirst.path().getTime()).isBetween(40_000L, 120_000L);

            RouteResult restrictedTurn = route(
                    hopper, 39.010000, 116.000000, 39.010000, 116.002000,
                    BlockedEdgeSnapshot.empty());
            assertThat(restrictedTurn.path().getDistance()).isBetween(220.0, 330.0);

            assertRestrictedAccessBehavior(hopper, 39.020000);
            assertRestrictedAccessBehavior(hopper, 39.030000);
            assertRestrictedAccessBehavior(hopper, 39.040000);

            int firstEdgeKey = firstEdgeKey(distanceFirst.path());
            int firstEdgeId = GHUtility.getEdgeFromEdgeKey(firstEdgeKey);
            RouteResult cameraDetour = route(
                    hopper, 39.000000, 116.000000, 39.000000, 116.002000,
                    BlockedEdgeSnapshot.blockBothDirections(List.of(firstEdgeId), "test"));
            assertThat(cameraDetour.path().getDistance()).isGreaterThan(240);
            assertThat(cameraDetour.audit().blockedRejections()).isPositive();
        } finally {
            hopper.close();
        }
    }

    private static RouteResult route(
            HardAvoidingGraphHopper hopper,
            double fromLat,
            double fromLon,
            double toLat,
            double toLon,
            BlockedEdgeSnapshot blockedEdges) {
        GHRequest request = new GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile("car")
                .setAlgorithm(ASTAR_BI)
                .setPathDetails(List.of(EDGE_KEY));
        request.getHints().putObject(Parameters.Routing.MAX_VISITED_NODES, 10_000);
        SearchAudit audit = new SearchAudit();
        GHResponse response = hopper.route(request, blockedEdges, audit);
        assertThat(response.getErrors()).isEmpty();
        return new RouteResult(response.getBest(), audit);
    }

    private static int firstEdgeKey(ResponsePath path) {
        List<PathDetail> edgeKeys = path.getPathDetails().get(EDGE_KEY);
        assertThat(edgeKeys).isNotEmpty();
        return (Integer) edgeKeys.getFirst().getValue();
    }

    private static void assertRestrictedAccessBehavior(
            HardAvoidingGraphHopper hopper,
            double latitude) {
        RouteResult avoidsRestrictedThroughRoad = route(
                hopper, latitude, 115.999000, latitude, 116.002000,
                BlockedEdgeSnapshot.empty());
        assertThat(avoidsRestrictedThroughRoad.path().getDistance()).isGreaterThan(330);
        assertThat(maximumLatitude(avoidsRestrictedThroughRoad.path()))
                .isGreaterThan(latitude + 0.0005);

        RouteResult startsInsideRestrictedRoad = route(
                hopper, latitude, 116.001000, latitude, 115.999000,
                BlockedEdgeSnapshot.empty());
        assertThat(startsInsideRestrictedRoad.path().getDistance()).isLessThan(220);
    }

    private static double maximumLatitude(ResponsePath path) {
        PointList points = path.getPoints();
        double maximum = -Double.MAX_VALUE;
        for (int index = 0; index < points.size(); index++) {
            maximum = Math.max(maximum, points.getLat(index));
        }
        return maximum;
    }

    private static AppProperties.Routing routing(
            Path osm,
            Path currentCache,
            Path candidateCache) {
        return new AppProperties.Routing(
                osm.toString(),
                currentCache.toString(),
                candidateCache.toString(),
                RoutingProfileMode.COMPLIANT_DISTANCE_V1,
                30,
                1,
                1,
                Duration.ofSeconds(5),
                10_000);
    }

    private static String osmFixture() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <osm version="0.6" generator="camera-safe-routing-test">
                  <bounds minlat="38.99" minlon="115.99" maxlat="39.05" maxlon="116.01"/>
                  <node id="1" lat="39.000000" lon="116.000000"/>
                  <node id="2" lat="39.000000" lon="116.001000"/>
                  <node id="3" lat="39.000000" lon="116.002000"/>
                  <node id="4" lat="39.001000" lon="116.001000"/>
                  <node id="10" lat="39.010000" lon="116.000000"/>
                  <node id="11" lat="39.010000" lon="116.001000"/>
                  <node id="12" lat="39.010000" lon="116.002000"/>
                  <node id="13" lat="39.010600" lon="116.000500"/>
                  <node id="20" lat="39.020000" lon="116.000000"/>
                  <node id="21" lat="39.020000" lon="116.001000"/>
                  <node id="22" lat="39.020000" lon="116.002000"/>
                  <node id="23" lat="39.021000" lon="116.001000"/>
                  <node id="24" lat="39.020000" lon="115.999000"/>
                  <node id="30" lat="39.030000" lon="116.000000"/>
                  <node id="31" lat="39.030000" lon="116.001000"/>
                  <node id="32" lat="39.030000" lon="116.002000"/>
                  <node id="33" lat="39.031000" lon="116.001000"/>
                  <node id="34" lat="39.030000" lon="115.999000"/>
                  <node id="40" lat="39.040000" lon="116.000000"/>
                  <node id="41" lat="39.040000" lon="116.001000"/>
                  <node id="42" lat="39.040000" lon="116.002000"/>
                  <node id="43" lat="39.041000" lon="116.001000"/>
                  <node id="44" lat="39.040000" lon="115.999000"/>

                  <way id="100">
                    <nd ref="1"/><nd ref="2"/><nd ref="3"/>
                    <tag k="highway" v="residential"/><tag k="maxspeed" v="10"/>
                  </way>
                  <way id="101">
                    <nd ref="1"/><nd ref="4"/><nd ref="3"/>
                    <tag k="highway" v="primary"/><tag k="maxspeed" v="100"/>
                  </way>
                  <way id="200">
                    <nd ref="10"/><nd ref="11"/><tag k="highway" v="residential"/>
                  </way>
                  <way id="201">
                    <nd ref="11"/><nd ref="12"/><tag k="highway" v="residential"/>
                  </way>
                  <way id="202">
                    <nd ref="10"/><nd ref="13"/><nd ref="11"/>
                    <tag k="highway" v="residential"/>
                  </way>
                  <way id="400">
                    <nd ref="20"/><nd ref="21"/><nd ref="22"/>
                    <tag k="highway" v="residential"/><tag k="access" v="private"/>
                  </way>
                  <way id="401">
                    <nd ref="20"/><nd ref="23"/><nd ref="22"/>
                    <tag k="highway" v="residential"/>
                  </way>
                  <way id="402">
                    <nd ref="24"/><nd ref="20"/><tag k="highway" v="residential"/>
                  </way>
                  <way id="500">
                    <nd ref="30"/><nd ref="31"/><nd ref="32"/>
                    <tag k="highway" v="residential"/><tag k="access" v="destination"/>
                  </way>
                  <way id="501">
                    <nd ref="30"/><nd ref="33"/><nd ref="32"/>
                    <tag k="highway" v="residential"/>
                  </way>
                  <way id="502">
                    <nd ref="34"/><nd ref="30"/><tag k="highway" v="residential"/>
                  </way>
                  <way id="600">
                    <nd ref="40"/><nd ref="41"/><nd ref="42"/>
                    <tag k="highway" v="residential"/><tag k="access" v="customers"/>
                  </way>
                  <way id="601">
                    <nd ref="40"/><nd ref="43"/><nd ref="42"/>
                    <tag k="highway" v="residential"/>
                  </way>
                  <way id="602">
                    <nd ref="44"/><nd ref="40"/><tag k="highway" v="residential"/>
                  </way>
                  <relation id="300">
                    <member type="way" ref="200" role="from"/>
                    <member type="node" ref="11" role="via"/>
                    <member type="way" ref="201" role="to"/>
                    <tag k="type" v="restriction"/>
                    <tag k="restriction" v="no_straight_on"/>
                  </relation>
                </osm>
                """;
    }

    private record RouteResult(ResponsePath path, SearchAudit audit) {
    }
}
