package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;

public interface RoutePlanner {
    PlannedRoute plan(
            Wgs84Coordinate start,
            Wgs84Coordinate end,
            RoutingSnapshot snapshot);
}
