package cn.camera.safe.routing;

import cn.camera.safe.config.SixthRingProperties;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.util.PMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

@Component
public final class SixthRingRoutingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SixthRingRoutingManager.class);

    private final GraphHopperManager graphManager;
    private final SixthRingProperties properties;
    private final SixthRingBoundaryLoader boundaryLoader;
    private final SixthRingPortalTopologyBuilder topologyBuilder;
    private final AtomicReference<GraphState> state = new AtomicReference<>(GraphState.NOT_STARTED);

    private volatile SixthRingRoutingContext context;
    private volatile String failureReason;

    @Autowired
    public SixthRingRoutingManager(
            GraphHopperManager graphManager,
            SixthRingProperties properties,
            SixthRingBoundaryLoader boundaryLoader) {
        this(graphManager, properties, boundaryLoader, new SixthRingPortalTopologyBuilder());
    }

    SixthRingRoutingManager(
            GraphHopperManager graphManager,
            SixthRingProperties properties,
            SixthRingBoundaryLoader boundaryLoader,
            SixthRingPortalTopologyBuilder topologyBuilder) {
        this.graphManager = graphManager;
        this.properties = properties;
        this.boundaryLoader = boundaryLoader;
        this.topologyBuilder = topologyBuilder;
    }

    public synchronized void initialize() {
        if (state.get() == GraphState.READY) {
            return;
        }
        if (!state.compareAndSet(GraphState.NOT_STARTED, GraphState.LOADING)
                && !state.compareAndSet(GraphState.FAILED, GraphState.LOADING)) {
            throw new IllegalStateException("六环路由拓扑初始化正在执行");
        }

        try {
            HardAvoidingGraphHopper hopper = graphManager.requireHopper();
            SixthRingBoundaryLoader.LoadedBoundary loaded = boundaryLoader.load(
                    Path.of(properties.boundaryPath()),
                    graphManager.requireSourcePbfSha256(),
                    properties.requireApprovedBoundary());
            Weighting profileWeighting = hopper.createWeighting(hopper.getProfile("car"), new PMap());
            if (!profileWeighting.hasTurnCosts()) {
                throw new IllegalStateException("六环多目标路线要求启用 turn costs 的合规路由缓存");
            }
            Weighting distanceWeighting = new DistanceFirstLegalityWeighting(profileWeighting);
            BooleanEncodedValue carAccess = hopper.getEncodingManager()
                    .getBooleanEncodedValue("car_access");
            SixthRingPortalTopology topology = topologyBuilder.build(
                    hopper.getBaseGraph(), carAccess, distanceWeighting, loaded.boundary());
            if (topology.outbound().portals().isEmpty() || topology.inbound().portals().isEmpty()) {
                throw new IllegalStateException("六环有向通行口拓扑为空");
            }

            context = new SixthRingRoutingContext(
                    loaded.boundary(), topology, distanceWeighting, loaded.approvedForProduction());
            failureReason = null;
            state.set(GraphState.READY);
            if (!loaded.approvedForProduction()) {
                LOGGER.warn("六环边界以开发候选状态加载，仍禁止据此声明生产可发布 边界版本={} 路径={}",
                        loaded.boundary().version(), loaded.sourcePath());
            }
            LOGGER.info("六环路由拓扑加载完成 边界版本={} 出环通行口={} 入环通行口={} 生产批准={}",
                    loaded.boundary().version(),
                    topology.outbound().portals().size(),
                    topology.inbound().portals().size(),
                    loaded.approvedForProduction());
        } catch (Exception exception) {
            context = null;
            failureReason = exception.getMessage();
            state.set(GraphState.FAILED);
            LOGGER.error("六环路由拓扑初始化失败 异常类型={} 错误信息={}",
                    exception.getClass().getName(), exception.getMessage());
            throw new IllegalStateException("六环路由拓扑初始化失败", exception);
        }
    }

    public boolean isReady() {
        return state.get() == GraphState.READY && context != null;
    }

    public String failureReason() {
        return failureReason;
    }

    SixthRingRoutingContext requireContext() {
        SixthRingRoutingContext value = context;
        if (!isReady() || value == null) {
            throw new IllegalStateException("六环路由拓扑尚未就绪");
        }
        return value;
    }
}
