package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

public interface RoutingEngine {
    EngineRoute route(Wgs84Coordinate start, Wgs84Coordinate end, RoutingSnapshot snapshot);
}
