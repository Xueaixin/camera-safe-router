package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.util.GHUtility;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

@Component
public final class GraphHopperManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphHopperManager.class);
    private static final String ENCODED_VALUES = "car_access|block_private=false,car_average_speed,road_access";
    private static final String SOURCE_HASH_FILE = "camera-safe-source.sha256";

    private final AppProperties properties;
    private final AtomicReference<GraphState> state = new AtomicReference<>(GraphState.NOT_STARTED);
    private volatile String failureReason;
    private volatile HardAvoidingGraphHopper hopper;
    private volatile RoadEdgeIndex roadEdgeIndex;
    private volatile String graphFingerprint;

    public GraphHopperManager(AppProperties properties) {
        this.properties = properties;
    }

    public synchronized void initialize() {
        if (state.get() == GraphState.READY) {
            return;
        }
        if (!state.compareAndSet(GraphState.NOT_STARTED, GraphState.LOADING)
                && !state.compareAndSet(GraphState.FAILED, GraphState.LOADING)) {
            throw new IllegalStateException("graph initialization is already running");
        }

        HardAvoidingGraphHopper candidate = configuredHopper();
        try {
            Path cache = Path.of(properties.routing().graphCachePath()).toAbsolutePath().normalize();
            Path pbf = Path.of(properties.routing().pbfPath()).toAbsolutePath().normalize();
            candidate.setGraphHopperLocation(cache.toString());

            String pbfHash;
            if (Files.isRegularFile(cache.resolve("properties"))) {
                pbfHash = verifyExistingCache(cache, pbf);
                if (!candidate.load()) {
                    throw new IllegalStateException("GraphHopper cache could not be loaded");
                }
                LOGGER.info("Loaded GraphHopper cache nodes={} edges={}",
                        candidate.getBaseGraph().getNodes(), candidate.getBaseGraph().getEdges());
            } else {
                if (!Files.isRegularFile(pbf)) {
                    throw new IllegalStateException("PBF is required when graph cache is absent");
                }
                pbfHash = Hashing.sha256(pbf);
                candidate.setOSMFile(pbf.toString());
                candidate.importOrLoad();
                writeCacheSourceHash(cache, pbfHash);
                LOGGER.info("Imported GraphHopper graph nodes={} edges={}",
                        candidate.getBaseGraph().getNodes(), candidate.getBaseGraph().getEdges());
            }

            String fingerprint = Hashing.sha256("graphhopper=11.0|pbf=" + pbfHash
                    + "|encoded=" + ENCODED_VALUES
                    + "|nodes=" + candidate.getBaseGraph().getNodes()
                    + "|edges=" + candidate.getBaseGraph().getEdges());
            BooleanEncodedValue carAccess = candidate.getEncodingManager()
                    .getBooleanEncodedValue("car_access");
            RoadEdgeIndex newRoadIndex = RoadEdgeIndex.build(
                    candidate.getBaseGraph(),
                    edge -> edge.get(carAccess) || edge.getReverse(carAccess),
                    "sha256:" + fingerprint);

            this.hopper = candidate;
            this.graphFingerprint = "sha256:" + fingerprint;
            this.roadEdgeIndex = newRoadIndex;
            this.failureReason = null;
            state.set(GraphState.READY);
            LOGGER.info("Road edge index ready indexedEdges={}", newRoadIndex.indexedEdgeCount());
        } catch (Exception exception) {
            candidate.close();
            this.failureReason = exception.getMessage();
            state.set(GraphState.FAILED);
            LOGGER.error("Graph initialization failed type={} message={}",
                    exception.getClass().getName(), exception.getMessage());
            throw new IllegalStateException("graph initialization failed", exception);
        }
    }

    public GraphState state() {
        return state.get();
    }

    public String failureReason() {
        return failureReason;
    }

    public boolean isReady() {
        return state.get() == GraphState.READY;
    }

    public HardAvoidingGraphHopper requireHopper() {
        HardAvoidingGraphHopper value = hopper;
        if (!isReady() || value == null) {
            throw new IllegalStateException("routing graph is not ready");
        }
        return value;
    }

    public RoadEdgeIndex requireRoadEdgeIndex() {
        RoadEdgeIndex value = roadEdgeIndex;
        if (!isReady() || value == null) {
            throw new IllegalStateException("road edge index is not ready");
        }
        return value;
    }

    public String requireGraphFingerprint() {
        if (!isReady() || graphFingerprint == null) {
            throw new IllegalStateException("graph fingerprint is not ready");
        }
        return graphFingerprint;
    }

    public boolean contains(Wgs84Coordinate coordinate) {
        return requireHopper().getBaseGraph().getBounds().contains(coordinate.lat(), coordinate.lng());
    }

    private static HardAvoidingGraphHopper configuredHopper() {
        HardAvoidingGraphHopper configured = new HardAvoidingGraphHopper();
        configured.setEncodedValuesString(ENCODED_VALUES);
        configured.setProfiles(new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json")));
        configured.getCHPreparationHandler().setCHProfiles();
        configured.getLMPreparationHandler().setLMProfiles();
        return configured;
    }

    private static String verifyExistingCache(Path cache, Path pbf) throws IOException {
        Path metadata = cache.resolve(SOURCE_HASH_FILE);
        if (!Files.isRegularFile(metadata)) {
            throw new IllegalStateException(
                    "existing graph cache lacks camera-safe source metadata; use a new empty cache directory");
        }
        String cachedHash = Files.readString(metadata, StandardCharsets.US_ASCII).trim();
        if (!cachedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("graph cache source metadata is invalid");
        }
        if (Files.isRegularFile(pbf)) {
            String currentHash = Hashing.sha256(pbf);
            if (!cachedHash.equalsIgnoreCase(currentHash)) {
                throw new IllegalStateException("configured PBF does not match the existing graph cache");
            }
        }
        return cachedHash.toLowerCase();
    }

    private static void writeCacheSourceHash(Path cache, String hash) throws IOException {
        Files.createDirectories(cache);
        Path target = cache.resolve(SOURCE_HASH_FILE);
        Path temporary = Files.createTempFile(cache, SOURCE_HASH_FILE, ".tmp");
        try {
            Files.writeString(temporary, hash + System.lineSeparator(), StandardCharsets.US_ASCII);
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("graph cache filesystem does not support atomic metadata publication", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @PreDestroy
    public synchronized void close() {
        HardAvoidingGraphHopper value = hopper;
        hopper = null;
        roadEdgeIndex = null;
        if (value != null) {
            value.close();
        }
    }
}
