package cn.camera.safe.api;

import cn.camera.safe.application.CameraRefreshService;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingSnapshotManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static cn.camera.safe.application.RoutePlanningServiceTest.properties;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminRefreshControllerTest {
    private CameraRefreshService refreshService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        refreshService = mock(CameraRefreshService.class);
        OperationsController controller = new OperationsController(
                mock(GraphHopperManager.class),
                mock(RoutingSnapshotManager.class),
                refreshService,
                new AdminAccessGuard(properties()),
                properties());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void acceptsLocalRefreshAndMapsConcurrentRefreshTo409() throws Exception {
        when(refreshService.requestRefresh()).thenReturn("job-1");
        mvc.perform(post("/api/v1/admin/camera-snapshots/refresh")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        when(refreshService.requestRefresh()).thenThrow(new BusinessException(
                HttpStatus.CONFLICT, ErrorCode.REFRESH_ALREADY_RUNNING, "刷新进行中"));
        mvc.perform(post("/api/v1/admin/camera-snapshots/refresh")
                        .with(request -> {
                            request.setRemoteAddr("::1");
                            return request;
                        }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_ALREADY_RUNNING"));
    }

    @Test
    void doesNotExposeRefreshToNonLoopbackClients() throws Exception {
        mvc.perform(post("/api/v1/admin/camera-snapshots/refresh")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.12");
                            return request;
                        }))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ROUTING_NOT_READY"));
    }
}
