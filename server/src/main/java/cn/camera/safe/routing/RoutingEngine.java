package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

public interface RoutingEngine {
    default EngineRoute route(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot) {
        return route(start, end, snapshot, EdgeTraversalConstraint.ALLOW_ALL);
    }

    EngineRoute route(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot,
            EdgeTraversalConstraint traversalConstraint);
}
