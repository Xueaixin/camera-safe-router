package cn.camera.safe.routing;

import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.config.RoutingProfileMode;
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
    private final ControlReleaseTopologyBuilder releaseTopologyBuilder;
    private final TollBoothSourceParser tollBoothSourceParser;
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
        this.releaseTopologyBuilder = new ControlReleaseTopologyBuilder();
        this.tollBoothSourceParser = new TollBoothSourceParser();
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
            BooleanEncodedValue carAccess = hopper.getEncodingManager()
                    .getBooleanEncodedValue("car_access");
            SixthRingPortalTopology topology = topologyBuilder.build(
                    hopper.getBaseGraph(), carAccess, profileWeighting, loaded.boundary());
            if (topology.outbound().portals().isEmpty() || topology.inbound().portals().isEmpty()) {
                throw new IllegalStateException("六环有向通行口拓扑为空");
            }

            RoutingProfileMode profileMode = graphManager.requireRoutingProfileMode();
            TollBoothSourceData tollBoothSourceData = profileMode == RoutingProfileMode.COMPLIANT_TIME_V2
                    ? tollBoothSourceParser.parse(graphManager.requireSourcePbfPath())
                    : null;
            RoadClassification roadClassification = profileMode == RoutingProfileMode.COMPLIANT_TIME_V2
                    ? RoadClassifier.classify(
                            hopper.getBaseGraph(),
                            hopper.getEncodingManager(),
                            loaded.boundary().controlledArea(),
                            loaded.boundary().sixthRingArea().orElseThrow(
                                    () -> new IllegalStateException(
                                            "COMPLIANT_TIME_V2 边界缺少六环内区辅助面")),
                            loaded.boundary().tongzhouArea().orElseThrow(
                                    () -> new IllegalStateException(
                                            "COMPLIANT_TIME_V2 边界缺少通州辅助面")),
                            profileWeighting,
                            tollBoothSourceData)
                    : null;
            RoadClassificationAudit roadAudit = roadClassification == null
                    ? null : roadClassification.audit();
            if (roadAudit != null) {
                roadAudit.validate();
                TollCorridorTopology.Audit tollAudit = roadClassification.tollCorridors().audit();
                if (tollAudit.entryCorridors() <= 0 || tollAudit.exitCorridors() <= 0) {
                    throw new IllegalStateException("当前 PBF 未识别出完整六环收费站入口/出口走廊");
                }
            } else {
                LOGGER.warn("路由模式 {} 不包含 V2 道路身份编码，跳过道路分类审计", profileMode);
            }
            RoadClassificationIndex roadClassificationIndex = roadClassification == null
                    ? RoadClassificationIndex.empty(graphManager.requireGraphFingerprint())
                    : roadClassification.index();
            ControlReleaseTopology releaseTopology = releaseTopologyBuilder.build(
                    hopper.getBaseGraph(), carAccess, topology, roadClassificationIndex);
            context = new SixthRingRoutingContext(
                    loaded.boundary(), topology, releaseTopology, profileWeighting,
                    new TimeFirstLegalityWeighting(profileWeighting),
                    roadClassificationIndex,
                    roadAudit,
                    roadClassification == null
                            ? TollCorridorTopology.empty(0)
                            : roadClassification.tollCorridors(),
                    roadClassification == null
                            ? HighwayInterchangeTopology.empty()
                            : roadClassification.interchangeTopology(),
                    loaded.approvedForProduction());
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
            if (roadAudit != null) {
                LOGGER.info("道路分类审计完成 六环关系边={} 六环主路way={} 六环可通行方向={} "
                                + "通州高速主路way={} 通州高速可通行方向={} 通州高速匝道边={} "
                                + "高速主路基础边={} 释放后连接匝道边={} 分类指纹={}",
                        roadAudit.sixthRingRelationEdges(),
                        roadAudit.sixthRingMainlineWays(),
                        roadAudit.sixthRingDrivableDirections(),
                        roadAudit.tongzhouMotorwayMainlineWays(),
                        roadAudit.tongzhouMotorwayDrivableDirections(),
                        roadAudit.tongzhouMotorwayLinkEdges(),
                        roadClassification.index().highwayMainlineEdgeCount(),
                        roadClassification.index().releasedConnectorEdgeCount(),
                        roadClassification.index().fingerprint());
                TollCorridorTopology.Audit tollAudit = roadClassification.tollCorridors().audit();
                LOGGER.info("六环收费站走廊审计完成 PBF收费节点={} 已映射={} 六环关联={} "
                                + "入口走廊={} 出口走廊={} 未确认关联节点={} 入口有向边={} 出口有向边={}",
                        tollAudit.sourceTollBooths(),
                        tollAudit.mappedTollBooths(),
                        tollAudit.sixthRingRelatedTollBooths(),
                        tollAudit.entryCorridors(),
                        tollAudit.exitCorridors(),
                        tollAudit.unresolvedRelatedTollBooths(),
                        roadClassification.index().sixthTollEntryEdgeKeyCount(),
                        roadClassification.index().sixthTollExitEdgeKeyCount());
                HighwayInterchangeTopology.Audit interchangeAudit =
                        roadClassification.interchangeTopology().audit();
                LOGGER.info("六环互转走廊审计完成 link分量={} 触六环={} 触H-T={} "
                                + "候选分量={} R->H-T={} H-T->R={} 未解决分量={} "
                                + "含收费节点排除={} 与收费站走廊重合排除={} "
                                + "入口有向边={} 出口有向边={}",
                        interchangeAudit.linkComponents(),
                        interchangeAudit.componentsTouchingRing(),
                        interchangeAudit.componentsTouchingHT(),
                        interchangeAudit.candidateComponents(),
                        interchangeAudit.rToHtCorridors(),
                        interchangeAudit.htToRCorridors(),
                        interchangeAudit.unresolvedComponents(),
                        interchangeAudit.excludedTollNodeChains(),
                        interchangeAudit.excludedTollCorridorOverlapChains(),
                        roadClassification.index().rhtEntryEdgeKeyCount(),
                        roadClassification.index().rhtExitEdgeKeyCount());
            }
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

    public SixthRingBoundary requireBoundary() {
        return requireContext().boundary();
    }

    public boolean isBoundaryApprovedForProduction() {
        return requireContext().approvedForProduction();
    }

    SixthRingRoutingContext requireContext() {
        SixthRingRoutingContext value = context;
        if (!isReady() || value == null) {
            throw new IllegalStateException("六环路由拓扑尚未就绪");
        }
        return value;
    }
}
