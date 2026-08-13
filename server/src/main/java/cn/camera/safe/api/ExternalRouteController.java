package cn.camera.safe.api;

import cn.camera.safe.api.model.ExternalRouteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 界外参考路线代理：转发 OSRM 公共实例（免费、无 key），
 * 供前端渲染界外参考路线（WGS84 输入/输出），避免浏览器直连境外服务的稳定性与 CORS 问题。
 */
@RestController
public final class ExternalRouteController {
    private static final String OSRM_ENDPOINT =
            "https://router.project-osrm.org/route/v1/driving";
    private static final double MIN_ROUTE_DISTANCE_METERS = 100;
    private static final int MAX_GEOMETRY_POINTS = 20_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ExternalRouteController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @GetMapping("/api/v1/external-route")
    public ExternalRouteResponse route(
            @RequestParam double fromLng,
            @RequestParam double fromLat,
            @RequestParam double toLng,
            @RequestParam double toLat) {
        validateCoordinate("起点", fromLng, fromLat);
        validateCoordinate("终点", toLng, toLat);
        if (haversineMeters(fromLng, fromLat, toLng, toLat) < MIN_ROUTE_DISTANCE_METERS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "起终点距离过近，无需规划界外参考路线");
        }
        String url = OSRM_ENDPOINT
                + "/" + fromLng + "," + fromLat + ";" + toLng + "," + toLat
                + "?overview=full&geometries=geojson&alternatives=true&steps=false";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "界外路线服务返回 " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String code = root.path("code").asText("ERROR");
            if (!"Ok".equals(code)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "界外路线服务异常：" + code);
            }
            List<ExternalRouteResponse.ExternalRoute> routes = new ArrayList<>();
            for (JsonNode route : root.path("routes")) {
                List<List<Double>> geometry = new ArrayList<>();
                for (JsonNode point : route.path("geometry").path("coordinates")) {
                    geometry.add(List.of(point.get(0).asDouble(), point.get(1).asDouble()));
                    if (geometry.size() >= MAX_GEOMETRY_POINTS) {
                        break;
                    }
                }
                routes.add(new ExternalRouteResponse.ExternalRoute(
                        route.path("distance").asDouble(),
                        Math.round(route.path("duration").asDouble()),
                        geometry));
            }
            return new ExternalRouteResponse(code, routes);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "界外路线服务暂不可用：" + exception.getMessage());
        }
    }

    private static void validateCoordinate(String label, double lng, double lat) {
        if (!Double.isFinite(lng) || !Double.isFinite(lat)
                || lng < 73 || lng > 136 || lat < 3 || lat > 54) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, label + "坐标超出支持范围");
        }
    }

    private static double haversineMeters(
            double fromLng, double fromLat, double toLng, double toLat) {
        double lat1 = Math.toRadians(fromLat);
        double lat2 = Math.toRadians(toLat);
        double dLat = Math.toRadians(toLat - fromLat);
        double dLng = Math.toRadians(toLng - fromLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
