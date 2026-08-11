package cn.camera.safe.api;

import cn.camera.safe.api.model.ControlledAreaResponse;
import cn.camera.safe.application.ControlledAreaQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class ControlledAreaController {
    private final ControlledAreaQueryService queryService;

    public ControlledAreaController(ControlledAreaQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/controlled-area")
    public ControlledAreaResponse current() {
        return queryService.current();
    }
}
