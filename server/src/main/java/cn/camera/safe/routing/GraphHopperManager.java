package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.routing.ev.BooleanEncodedValue;
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
    private static final String SOURCE_HASH_FILE = "camera-safe-source.sha256";
    private static final String CONFIGURATION_HASH_FILE = "camera-safe-routing-config.sha256";

    private final AppProperties properties;
    private final AtomicReference<GraphState> state = new AtomicReference<>(GraphState.NOT_STARTED);
    private volatile String failureReason;
    private volatile HardAvoidingGraphHopper hopper;
    private volatile RoadEdgeIndex roadEdgeIndex;
    private volatile String graphFingerprint;
    private volatile String sourcePbfSha256;

    public GraphHopperManager(AppProperties properties) {
        this.properties = properties;
    }

    public synchronized void initialize() {
        if (state.get() == GraphState.READY) {
            return;
        }
        if (!state.compareAndSet(GraphState.NOT_STARTED, GraphState.LOADING)
                && !state.compareAndSet(GraphState.FAILED, GraphState.LOADING)) {
            throw new IllegalStateException("路网初始化正在执行");
        }

        HardAvoidingGraphHopper candidate = null;
        try {
            RoutingGraphConfiguration graphConfiguration =
                    RoutingGraphConfiguration.resolve(properties.routing());
            candidate = graphConfiguration.createHopper();
            Path cache = graphConfiguration.cachePath();
            Path pbf = Path.of(properties.routing().pbfPath()).toAbsolutePath().normalize();
            candidate.setGraphHopperLocation(cache.toString());
            LOGGER.info("准备初始化路网 路由模式={} 缓存目录={}",
                    graphConfiguration.mode(), cache);

            String pbfHash;
            if (Files.isRegularFile(cache.resolve("properties"))) {
                pbfHash = verifyExistingCache(cache, pbf, graphConfiguration);
                if (!candidate.load()) {
                    throw new IllegalStateException("GraphHopper 路网缓存无法加载");
                }
                LOGGER.info("GraphHopper 路网缓存加载完成 节点数={} 边数={}",
                        candidate.getBaseGraph().getNodes(), candidate.getBaseGraph().getEdges());
            } else {
                if (!Files.isRegularFile(pbf)) {
                    throw new IllegalStateException("路网缓存不存在时必须提供 PBF 文件");
                }
                pbfHash = Hashing.sha256(pbf);
                candidate.setOSMFile(pbf.toString());
                candidate.importOrLoad();
                writeCacheMetadata(cache, SOURCE_HASH_FILE, pbfHash);
                writeCacheMetadata(
                        cache,
                        CONFIGURATION_HASH_FILE,
                        graphConfiguration.compatibilityHash());
                LOGGER.info("GraphHopper 路网导入完成 节点数={} 边数={}",
                        candidate.getBaseGraph().getNodes(), candidate.getBaseGraph().getEdges());
            }

            String fingerprint = Hashing.sha256("graphhopper=11.0|pbf=" + pbfHash
                    + "|routingConfig=" + graphConfiguration.compatibilityHash()
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
            this.sourcePbfSha256 = pbfHash;
            this.roadEdgeIndex = newRoadIndex;
            this.failureReason = null;
            state.set(GraphState.READY);
            LOGGER.info("道路边索引准备完成 索引边数={}", newRoadIndex.indexedEdgeCount());
        } catch (Exception exception) {
            if (candidate != null) {
                candidate.close();
            }
            this.failureReason = exception.getMessage();
            state.set(GraphState.FAILED);
            LOGGER.error("路网初始化失败 异常类型={} 错误信息={}",
                    exception.getClass().getName(), exception.getMessage());
            throw new IllegalStateException("路网初始化失败", exception);
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
            throw new IllegalStateException("路网尚未就绪");
        }
        return value;
    }

    public RoadEdgeIndex requireRoadEdgeIndex() {
        RoadEdgeIndex value = roadEdgeIndex;
        if (!isReady() || value == null) {
            throw new IllegalStateException("道路边索引尚未就绪");
        }
        return value;
    }

    public String requireGraphFingerprint() {
        if (!isReady() || graphFingerprint == null) {
            throw new IllegalStateException("路网指纹尚未就绪");
        }
        return graphFingerprint;
    }

    public boolean contains(Wgs84Coordinate coordinate) {
        return requireHopper().getBaseGraph().getBounds().contains(coordinate.lat(), coordinate.lng());
    }

    private static String verifyExistingCache(
            Path cache,
            Path pbf,
            RoutingGraphConfiguration graphConfiguration) throws IOException {
        Path metadata = cache.resolve(SOURCE_HASH_FILE);
        if (!Files.isRegularFile(metadata)) {
            throw new IllegalStateException(
                    "现有路网缓存缺少源文件元数据，请使用新的空缓存目录");
        }
        String cachedHash = Files.readString(metadata, StandardCharsets.US_ASCII).trim();
        if (!cachedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("路网缓存的源文件元数据无效");
        }
        if (Files.isRegularFile(pbf)) {
            String currentHash = Hashing.sha256(pbf);
            if (!cachedHash.equalsIgnoreCase(currentHash)) {
                throw new IllegalStateException("配置的 PBF 与现有路网缓存不匹配");
            }
        }
        verifyConfigurationHash(cache, graphConfiguration);
        return cachedHash.toLowerCase();
    }

    public String requireSourcePbfSha256() {
        if (!isReady() || sourcePbfSha256 == null) {
            throw new IllegalStateException("路网源 PBF 指纹尚未就绪");
        }
        return sourcePbfSha256;
    }

    static void verifyConfigurationHash(
            Path cache,
            RoutingGraphConfiguration graphConfiguration) throws IOException {
        Path metadata = cache.resolve(CONFIGURATION_HASH_FILE);
        if (!Files.isRegularFile(metadata)) {
            if (graphConfiguration.requiresCompatibilityMetadata()) {
                throw new IllegalStateException(
                        "候选路网缓存缺少路由配置元数据，请使用新的空缓存目录重建");
            }
            return;
        }
        String cachedHash = Files.readString(metadata, StandardCharsets.US_ASCII).trim();
        if (!cachedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("路网缓存的路由配置元数据无效");
        }
        if (!cachedHash.equalsIgnoreCase(graphConfiguration.compatibilityHash())) {
            throw new IllegalStateException("路网缓存与当前路由配置不兼容，请使用新的空缓存目录重建");
        }
    }

    private static void writeCacheMetadata(
            Path cache,
            String fileName,
            String value) throws IOException {
        Files.createDirectories(cache);
        Path target = cache.resolve(fileName);
        Path temporary = Files.createTempFile(cache, fileName, ".tmp");
        try {
            Files.writeString(temporary, value + System.lineSeparator(), StandardCharsets.US_ASCII);
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("路网缓存所在文件系统不支持原子发布元数据", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @PreDestroy
    public synchronized void close() {
        HardAvoidingGraphHopper value = hopper;
        hopper = null;
        roadEdgeIndex = null;
        sourcePbfSha256 = null;
        if (value != null) {
            value.close();
        }
    }
}
