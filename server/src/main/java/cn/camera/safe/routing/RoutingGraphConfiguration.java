package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.RoutingProfileMode;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.TurnCostsConfig;

import java.nio.file.Path;

final class RoutingGraphConfiguration {
    static final String LEGACY_ENCODED_VALUES =
            "car_access|block_private=false,car_average_speed,road_access";
    static final String TIME_V2_ENCODED_VALUES =
            "car_access|block_private=false,car_average_speed,road_access,"
                    + "road_class,road_class_link,road_environment,osm_way_id,"
                    + RoadIdentityEncodedValues.SIXTH_RING_MAINLINE;
    static final double DISTANCE_INFLUENCE_SECONDS_PER_KILOMETER = 1_000_000.0;
    static final double RESTRICTED_ACCESS_ENTRY_PENALTY_SECONDS = 1_000_000_000.0;

    private static final String RESTRICTED_ACCESS_CONDITION =
            "prev_road_access != road_access && ("
                    + "road_access == DESTINATION || road_access == CUSTOMERS || "
                    + "road_access == DELIVERY || road_access == PRIVATE || "
                    + "road_access == AGRICULTURAL || road_access == FORESTRY)";

    private final RoutingProfileMode mode;
    private final Path cachePath;
    private final String encodedValues;
    private final String compatibilityHash;

    private RoutingGraphConfiguration(
            RoutingProfileMode mode,
            Path cachePath,
            String encodedValues,
            String compatibilityHash) {
        this.mode = mode;
        this.cachePath = cachePath;
        this.encodedValues = encodedValues;
        this.compatibilityHash = compatibilityHash;
    }

    static RoutingGraphConfiguration resolve(AppProperties.Routing properties) {
        Path currentCache = normalize(properties.graphCachePath());
        Path candidateCache = normalize(properties.candidateGraphCachePath());
        RoutingProfileMode mode = properties.profileMode();
        if (mode != RoutingProfileMode.CURRENT && currentCache.equals(candidateCache)) {
            throw new IllegalStateException("候选路由缓存目录不能与当前路由缓存目录相同");
        }

        Path effectiveCache = mode == RoutingProfileMode.CURRENT ? currentCache : candidateCache;
        String encodedValues = mode == RoutingProfileMode.COMPLIANT_TIME_V2
                ? TIME_V2_ENCODED_VALUES
                : LEGACY_ENCODED_VALUES;
        return new RoutingGraphConfiguration(
                mode,
                effectiveCache,
                encodedValues,
                Hashing.sha256(compatibilityMaterial(mode, encodedValues)));
    }

    HardAvoidingGraphHopper createHopper() {
        HardAvoidingGraphHopper hopper = new HardAvoidingGraphHopper();
        hopper.setEncodedValuesString(encodedValues);
        hopper.setProfiles(createProfile());
        hopper.getCHPreparationHandler().setCHProfiles();
        hopper.getLMPreparationHandler().setLMProfiles();
        String graphStorage = System.getProperty("routing.graph.storage", "");
        if (!graphStorage.isBlank()) {
            hopper.setGraphStorage(graphStorage);
        }
        return hopper;
    }

    Profile createProfile() {
        if (mode == RoutingProfileMode.CURRENT) {
            return new Profile("car")
                    .setCustomModel(GHUtility.loadCustomModelFromJar("car.json"));
        }

        CustomModel model = GHUtility.loadCustomModelFromJar("car.json")
                .addToPriority(Statement.If(
                        "road_access == NO",
                        Statement.Op.MULTIPLY,
                        "0"))
                .addToTurnPenalty(Statement.If(
                        RESTRICTED_ACCESS_CONDITION,
                        Statement.Op.ADD,
                        Double.toString(RESTRICTED_ACCESS_ENTRY_PENALTY_SECONDS)));
        if (mode == RoutingProfileMode.COMPLIANT_DISTANCE_V1) {
            model.setDistanceInfluence(DISTANCE_INFLUENCE_SECONDS_PER_KILOMETER);
        }
        return new Profile("car")
                .setTurnCostsConfig(TurnCostsConfig.car()
                        .setUTurnCosts(TurnCostsConfig.INFINITE_U_TURN_COSTS))
                .setCustomModel(model);
    }

    RoutingProfileMode mode() {
        return mode;
    }

    Path cachePath() {
        return cachePath;
    }

    String encodedValues() {
        return encodedValues;
    }

    String compatibilityHash() {
        return compatibilityHash;
    }

    boolean requiresCompatibilityMetadata() {
        return mode != RoutingProfileMode.CURRENT;
    }

    private static Path normalize(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    private static String compatibilityMaterial(
            RoutingProfileMode mode,
            String encodedValues) {
        if (mode == RoutingProfileMode.CURRENT) {
            return "graphhopper=11.0|mode=CURRENT|encoded=" + encodedValues
                    + "|model=builtin-car.json";
        }
        return "graphhopper=11.0|mode=" + mode + "|encoded=" + encodedValues
                + "|turnVehicleTypes=motorcar,motor_vehicle|uTurnCosts=infinite"
                + "|distanceInfluence="
                + (mode == RoutingProfileMode.COMPLIANT_DISTANCE_V1
                        ? DISTANCE_INFLUENCE_SECONDS_PER_KILOMETER : "builtin")
                + "|restrictedAccessCondition=" + RESTRICTED_ACCESS_CONDITION
                + "|restrictedAccessEntryPenalty=" + RESTRICTED_ACCESS_ENTRY_PENALTY_SECONDS;
    }
}
