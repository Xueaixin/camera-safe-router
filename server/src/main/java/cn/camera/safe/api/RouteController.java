package cn.camera.safe.api;

import cn.camera.safe.api.model.RouteRequest;
import cn.camera.safe.api.model.RouteResponse;
import cn.camera.safe.application.RoutePlanningService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
public final class RouteController {
    private final RoutePlanningService routePlanningService;

    public RouteController(RoutePlanningService routePlanningService) {
        this.routePlanningService = routePlanningService;
    }

    @PostMapping
    public RouteResponse planRoute(@Valid @RequestBody RouteRequest request) {
        return routePlanningService.plan(request);
    }
}
