package cn.camera.safe.api;

import cn.camera.safe.application.CameraRefreshService;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.GraphHopperManager;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static cn.camera.safe.application.RoutePlanningServiceTest.properties;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationsControllerTest {
    @Test
    void healthIsUpWhileReadinessTracksGraphSnapshotAndBlockedVersions() throws Exception {
        GraphHopperManager graph = mock(GraphHopperManager.class);
        RoutingSnapshotManager snapshots = mock(RoutingSnapshotManager.class);
        CameraRefreshService refresh = mock(CameraRefreshService.class);
        OperationsController controller = new OperationsController(
                graph, snapshots, refresh, new AdminAccessGuard(properties()), properties());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .addFilters(new RequestIdFilter())
                .build();

        when(graph.isReady()).thenReturn(false);
        when(snapshots.current()).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/v1/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.graphLoaded").value(false));

        when(graph.isReady()).thenReturn(true);
        when(snapshots.current()).thenReturn(Optional.of(snapshot()));
        mvc.perform(get("/api/v1/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.graphLoaded").value(true))
                .andExpect(jsonPath("$.cameraSnapshotLoaded").value(true))
                .andExpect(jsonPath("$.blockedEdgesLoaded").value(true));
    }

    private static RoutingSnapshot snapshot() {
        Wgs84Coordinate coordinate = new Wgs84Coordinate(116.4, 39.9);
        CameraPoint camera = new CameraPoint(
                "camera", "district", new Gcj02Coordinate(116.4, 39.9), coordinate,
                "address", "type", null);
        CameraSnapshot cameras = new CameraSnapshot(
                "camera-v1", "hash", Instant.EPOCH, List.of(camera));
        return new RoutingSnapshot(
                cameras,
                new CameraSpatialIndex(cameras.cameras()),
                new BlockedEdgeSnapshot(new java.util.BitSet(), new java.util.BitSet(),
                        "camera-v1", "blocked-v1"),
                "graph-v1", 30, 1, List.of());
    }
}
