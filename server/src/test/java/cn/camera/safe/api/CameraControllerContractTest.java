package cn.camera.safe.api;

import cn.camera.safe.api.model.CameraPage;
import cn.camera.safe.api.model.CameraView;
import cn.camera.safe.application.CameraQueryService;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.BlockedEdgeSnapshot;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CameraControllerContractTest {
    private CameraQueryService queryService;
    private RoutingSnapshotManager snapshotManager;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryService = mock(CameraQueryService.class);
        snapshotManager = mock(RoutingSnapshotManager.class);
        mvc = MockMvcBuilders.standaloneSetup(new CameraController(queryService, snapshotManager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void returnsCameraPageAndCurrentSnapshotShapes() throws Exception {
        when(queryService.query(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(CoordinateSystem.GCJ02)))
                .thenReturn(new CameraPage(
                        CoordinateSystem.GCJ02,
                        "camera-v1",
                        List.of(new CameraView(
                                "camera", 116.4, 39.9, "address", "type", "东向西"))));
        when(snapshotManager.current()).thenReturn(Optional.of(snapshot()));

        mvc.perform(get("/api/v1/cameras")
                        .param("minLng", "116.3")
                        .param("minLat", "39.8")
                        .param("maxLng", "116.5")
                        .param("maxLat", "40.0")
                        .param("coordinateSystem", "GCJ02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinateSystem").value("GCJ02"))
                .andExpect(jsonPath("$.snapshotVersion").value("camera-v1"))
                .andExpect(jsonPath("$.items[0].id").value("camera"));

        mvc.perform(get("/api/v1/camera-snapshots/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.snapshotVersion").value("camera-v1"))
                .andExpect(jsonPath("$.blockedEdgeVersion").value("blocked-v1"))
                .andExpect(jsonPath("$.cameraCount").value(1))
                .andExpect(jsonPath("$.safetyRadiusMeters").value(30));
    }

    @Test
    void rejectsUnknownCoordinateSystemAndReportsMissingSnapshot() throws Exception {
        mvc.perform(get("/api/v1/cameras")
                        .param("minLng", "116.3")
                        .param("minLat", "39.8")
                        .param("maxLng", "116.5")
                        .param("maxLat", "40.0")
                        .param("coordinateSystem", "BD09"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_COORDINATE_SYSTEM"));

        when(snapshotManager.current()).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/camera-snapshots/current"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CAMERA_SNAPSHOT_NOT_READY"));
    }

    @Test
    void returnsAnExplicitErrorWhenTheActualCameraResultLimitIsExceeded() throws Exception {
        when(queryService.query(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(CoordinateSystem.GCJ02)))
                .thenThrow(new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        ErrorCode.CAMERA_QUERY_RESULT_LIMIT_EXCEEDED,
                        "查询范围内点位超过服务返回上限，请放大地图后重试"));

        mvc.perform(get("/api/v1/cameras")
                        .param("minLng", "115")
                        .param("minLat", "39")
                        .param("maxLng", "117")
                        .param("maxLat", "41")
                        .param("coordinateSystem", "GCJ02"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMERA_QUERY_RESULT_LIMIT_EXCEEDED"));
    }

    static RoutingSnapshot snapshot() {
        Wgs84Coordinate coordinate = new Wgs84Coordinate(116.4, 39.9);
        CameraPoint camera = new CameraPoint(
                "camera", "district", new Gcj02Coordinate(116.4, 39.9), coordinate,
                "address", "type", "东向西");
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
