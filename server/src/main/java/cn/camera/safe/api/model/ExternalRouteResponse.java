package cn.camera.safe.api.model;

import java.util.List;

public record ExternalRouteResponse(
        String code,
        List<ExternalRoute> routes) {

    public ExternalRouteResponse {
        routes = List.copyOf(routes);
    }

    public record ExternalRoute(
            double distanceMeters,
            long durationSeconds,
            List<List<Double>> geometry) {

        public ExternalRoute {
            geometry = List.copyOf(geometry);
        }
    }
}
