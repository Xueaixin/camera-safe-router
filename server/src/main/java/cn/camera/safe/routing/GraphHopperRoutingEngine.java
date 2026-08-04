package cn.camera.safe.routing;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.exceptions.MaximumNodesExceededException;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.graphhopper.util.Parameters.Algorithms.ASTAR_BI;
import static com.graphhopper.util.Parameters.Details.EDGE_KEY;

@Component
public final class GraphHopperRoutingEngine implements RoutingEngine {
    private static final long INTERNAL_TIMEOUT_GRACE_MILLIS = 500;

    private final GraphHopperManager graphManager;
    private final AppProperties properties;

    public GraphHopperRoutingEngine(GraphHopperManager graphManager, AppProperties properties) {
        this.graphManager = graphManager;
        this.properties = properties;
    }

    @Override
    public EngineRoute route(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        GHRequest request = new GHRequest(start.lat(), start.lng(), end.lat(), end.lng())
                .setProfile("car")
                .setAlgorithm(ASTAR_BI)
                .setPathDetails(List.of(EDGE_KEY));
        request.getHints().putObject(
                Parameters.Routing.MAX_VISITED_NODES, properties.routing().maxVisitedNodes());
        request.getHints().putObject(
                Parameters.Routing.TIMEOUT_MS,
                properties.routing().requestTimeout().toMillis() + INTERNAL_TIMEOUT_GRACE_MILLIS);

        SearchAudit audit = new SearchAudit();
        GHResponse response = graphManager.requireHopper().route(request, snapshot.blockedEdges(), audit);
        if (response.hasErrors()) {
            RoutingEngineException.Reason reason = classifyErrors(response.getErrors());
            String message = switch (reason) {
                case POINT_NOT_FOUND -> "路线点无法吸附到路网";
                case RESOURCE_LIMIT -> "路线搜索达到资源上限";
                case NO_ROUTE -> "未找到可用路线";
            };
            throw new RoutingEngineException(reason, message);
        }

        ResponsePath best = response.getBest();
        rejectBlockedEdgesInExtractedPath(best, snapshot.blockedEdges());
        PointList points = best.getPoints();
        List<Wgs84Coordinate> geometry = java.util.stream.IntStream.range(0, points.size())
                .mapToObj(index -> new Wgs84Coordinate(points.getLon(index), points.getLat(index)))
                .toList();
        if (geometry.size() < 2) {
            throw new RoutingEngineException(RoutingEngineException.Reason.NO_ROUTE,
                    "路线几何为空");
        }
        return new EngineRoute(
                best.getDistance(),
                best.getTime(),
                geometry,
                audit.edgeChecks(),
                audit.virtualEdgeChecks(),
                audit.blockedRejections());
    }

    static RoutingEngineException.Reason classifyErrors(List<Throwable> errors) {
        if (errors.stream().anyMatch(
                error -> error.getClass().getSimpleName().contains("PointNotFound"))) {
            return RoutingEngineException.Reason.POINT_NOT_FOUND;
        }
        if (errors.stream().anyMatch(MaximumNodesExceededException.class::isInstance)) {
            return RoutingEngineException.Reason.RESOURCE_LIMIT;
        }
        return RoutingEngineException.Reason.NO_ROUTE;
    }

    private static void rejectBlockedEdgesInExtractedPath(
            ResponsePath path,
            BlockedEdgeSnapshot blockedEdges) {
        List<PathDetail> details = path.getPathDetails().get(EDGE_KEY);
        if (details == null) {
            throw new IllegalStateException("GraphHopper 未返回 edge_key 路径明细");
        }
        boolean violation = details.stream()
                .map(PathDetail::getValue)
                .map(Integer.class::cast)
                .anyMatch(blockedEdges::isBlockedEdgeKey);
        if (violation) {
            throw new IllegalStateException("GraphHopper 返回的路线包含禁行边");
        }
    }
}
