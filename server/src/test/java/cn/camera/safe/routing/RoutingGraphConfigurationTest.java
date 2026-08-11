package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.RoadAccess;
import com.graphhopper.util.CustomModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingGraphConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsCurrentProfileOnCurrentCache() {
        Path currentCache = temporaryDirectory.resolve("current");
        Path candidateCache = temporaryDirectory.resolve("candidate");

        RoutingGraphConfiguration configuration = RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.CURRENT, currentCache, candidateCache));
        Profile profile = configuration.createProfile();

        assertThat(configuration.cachePath()).isEqualTo(currentCache.toAbsolutePath());
        assertThat(configuration.requiresCompatibilityMetadata()).isFalse();
        assertThat(configuration.encodedValues())
                .isEqualTo(RoutingGraphConfiguration.LEGACY_ENCODED_VALUES);
        assertThat(profile.hasTurnCosts()).isFalse();
        assertThat(profile.getCustomModel().getDistanceInfluence()).isEqualTo(90.0);
    }

    @Test
    void candidateProfileUsesIndependentCacheAndExplicitLegalityRules() {
        Path currentCache = temporaryDirectory.resolve("current");
        Path candidateCache = temporaryDirectory.resolve("candidate");

        RoutingGraphConfiguration current = RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.CURRENT, currentCache, candidateCache));
        RoutingGraphConfiguration candidate = RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.COMPLIANT_DISTANCE_V1, currentCache, candidateCache));
        Profile profile = candidate.createProfile();
        CustomModel model = profile.getCustomModel();
        String statements = model.getPriority() + " " + model.getTurnPenalty();

        assertThat(candidate.cachePath()).isEqualTo(candidateCache.toAbsolutePath());
        assertThat(candidate.requiresCompatibilityMetadata()).isTrue();
        assertThat(candidate.encodedValues())
                .isEqualTo(RoutingGraphConfiguration.LEGACY_ENCODED_VALUES);
        assertThat(candidate.compatibilityHash()).matches("[0-9a-f]{64}");
        assertThat(candidate.compatibilityHash()).isNotEqualTo(current.compatibilityHash());
        assertThat(profile.hasTurnCosts()).isTrue();
        assertThat(profile.getTurnCostsConfig().getVehicleTypes())
                .containsExactly("motorcar", "motor_vehicle");
        assertThat(model.getDistanceInfluence())
                .isEqualTo(RoutingGraphConfiguration.DISTANCE_INFLUENCE_SECONDS_PER_KILOMETER);
        assertThat(statements)
                .contains("road_access == " + RoadAccess.NO.name())
                .contains("prev_road_access != road_access")
                .contains(RoadAccess.DESTINATION.name())
                .contains(RoadAccess.CUSTOMERS.name())
                .contains(RoadAccess.DELIVERY.name())
                .contains(RoadAccess.PRIVATE.name())
                .contains(RoadAccess.AGRICULTURAL.name())
                .contains(RoadAccess.FORESTRY.name());
    }

    @Test
    void timeProfileUsesTravelTimeBeforeDistance() {
        Path currentCache = temporaryDirectory.resolve("current");
        Path candidateCache = temporaryDirectory.resolve("candidate");

        RoutingGraphConfiguration distance = RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.COMPLIANT_DISTANCE_V1, currentCache, candidateCache));
        RoutingGraphConfiguration time = RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.COMPLIANT_TIME_V2, currentCache, candidateCache));

        assertThat(time.createProfile().getCustomModel().getDistanceInfluence()).isEqualTo(90.0);
        assertThat(time.createProfile().hasTurnCosts()).isTrue();
        assertThat(time.cachePath()).isEqualTo(candidateCache.toAbsolutePath());
        assertThat(time.requiresCompatibilityMetadata()).isTrue();
        assertThat(time.compatibilityHash()).isNotEqualTo(distance.compatibilityHash());
        assertThat(time.encodedValues())
                .isEqualTo(RoutingGraphConfiguration.TIME_V2_ENCODED_VALUES)
                .contains("osm_way_id")
                .contains(RoadIdentityEncodedValues.SIXTH_RING_MAINLINE);
    }

    @Test
    void rejectsCandidateCacheThatAliasesCurrentCache() {
        Path cache = temporaryDirectory.resolve("same");

        assertThatThrownBy(() -> RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.COMPLIANT_DISTANCE_V1, cache, cache)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能与当前路由缓存目录相同");
        assertThatThrownBy(() -> RoutingGraphConfiguration.resolve(
                routing(RoutingProfileMode.COMPLIANT_TIME_V2, cache, cache)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能与当前路由缓存目录相同");
    }

    @Test
    void candidateCacheRequiresMatchingCompatibilityMetadata() throws Exception {
        Path cache = temporaryDirectory.resolve("candidate");
        Files.createDirectories(cache);
        RoutingGraphConfiguration configuration = RoutingGraphConfiguration.resolve(
                routing(
                        RoutingProfileMode.COMPLIANT_TIME_V2,
                        temporaryDirectory.resolve("current"),
                        cache));

        assertThatThrownBy(() -> GraphHopperManager.verifyConfigurationHash(cache, configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少路由配置元数据");

        Files.writeString(cache.resolve("camera-safe-routing-config.sha256"), "0".repeat(64));
        assertThatThrownBy(() -> GraphHopperManager.verifyConfigurationHash(cache, configuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不兼容");

        Files.writeString(
                cache.resolve("camera-safe-routing-config.sha256"),
                configuration.compatibilityHash());
        GraphHopperManager.verifyConfigurationHash(cache, configuration);
    }

    private static AppProperties.Routing routing(
            RoutingProfileMode mode,
            Path currentCache,
            Path candidateCache) {
        return new AppProperties.Routing(
                currentCache.resolveSibling("test.osm").toString(),
                currentCache.toString(),
                candidateCache.toString(),
                mode,
                30,
                1,
                1,
                Duration.ofSeconds(2),
                10_000);
    }
}
