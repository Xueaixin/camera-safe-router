package cn.camera.safe.api;

import cn.camera.safe.api.model.BoundaryCrossing;
import cn.camera.safe.api.model.BoundaryDirection;
import cn.camera.safe.api.model.BoundaryRole;
import cn.camera.safe.api.model.OutputCoordinate;
import cn.camera.safe.api.model.RoutePlanningMode;
import cn.camera.safe.api.model.RouteResponse;
import cn.camera.safe.api.model.RouteSegment;
import cn.camera.safe.application.RoutePlanningService;
import cn.camera.safe.coordinate.CoordinateSystem;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RouteControllerContractTest {
    private RoutePlanningService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RoutePlanningService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new RouteController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void returnsTheFrozenSuccessfulRouteShape() throws Exception {
        List<OutputCoordinate> safeGeometry = List.of(
                new OutputCoordinate(116.39, 39.9),
                new OutputCoordinate(116.40, 39.905));
        List<OutputCoordinate> referenceGeometry = List.of(
                new OutputCoordinate(116.40, 39.905),
                new OutputCoordinate(116.41, 39.91));
        when(service.plan(any())).thenReturn(new RouteResponse(
                "route-1",
                CoordinateSystem.GCJ02,
                RoutePlanningMode.CROSS_BOUNDARY_OUTBOUND,
                "sixth-ring-v1",
                BoundaryDirection.OUTBOUND,
                new BoundaryCrossing(
                        "P0001",
                        "六环路",
                        BoundaryDirection.OUTBOUND,
                        BoundaryRole.OUTER_EXIT,
                        new OutputCoordinate(116.399, 39.904),
                        new OutputCoordinate(116.405, 39.905)),
                new RouteSegment(400.5, 45, safeGeometry),
                new RouteSegment(600, 75, referenceGeometry),
                1_000.5,
                120,
                0,
                "camera-v1", "blocked-v1",
                List.of(new OutputCoordinate(116.39, 39.9), new OutputCoordinate(116.41, 39.91)),
                List.of()));

        mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value("route-1"))
                .andExpect(jsonPath("$.coordinateSystem").value("GCJ02"))
                .andExpect(jsonPath("$.planningMode").value("CROSS_BOUNDARY_OUTBOUND"))
                .andExpect(jsonPath("$.boundaryVersion").value("sixth-ring-v1"))
                .andExpect(jsonPath("$.boundaryDirection").value("OUTBOUND"))
                .andExpect(jsonPath("$.boundaryCrossing.portalId").value("P0001"))
                .andExpect(jsonPath("$.boundaryCrossing.wgs84.lng").value(116.399))
                .andExpect(jsonPath("$.boundaryCrossing.gcj02.lng").value(116.405))
                .andExpect(jsonPath("$.safeSegment.geometry.length()").value(2))
                .andExpect(jsonPath("$.referenceSegment.geometry.length()").value(2))
                .andExpect(jsonPath("$.cameraConflictCount").value(0))
                .andExpect(jsonPath("$.cameraSnapshotVersion").value("camera-v1"))
                .andExpect(jsonPath("$.blockedEdgeVersion").value("blocked-v1"))
                .andExpect(jsonPath("$.geometry.length()").value(2))
                .andExpect(jsonPath("$.steps").isArray());
    }

    @Test
    void includesRequiredNullableFieldsForInternalRoutes() throws Exception {
        List<OutputCoordinate> geometry = List.of(
                new OutputCoordinate(116.39, 39.9),
                new OutputCoordinate(116.41, 39.91));
        when(service.plan(any())).thenReturn(new RouteResponse(
                "route-2",
                CoordinateSystem.GCJ02,
                RoutePlanningMode.INTERNAL_SAFE,
                "sixth-ring-v1",
                null,
                null,
                new RouteSegment(1_000.5, 120, geometry),
                null,
                1_000.5,
                120,
                0,
                "camera-v1",
                "blocked-v1",
                geometry,
                List.of()));

        mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boundaryDirection").value(nullValue()))
                .andExpect(jsonPath("$.boundaryCrossing").value(nullValue()))
                .andExpect(jsonPath("$.referenceSegment").value(nullValue()));
    }

    @Test
    void rejectsUnknownFieldsAndUnknownCoordinateSystems() throws Exception {
        mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("\"vehicle\": \"CAR\"",
                                "\"vehicle\": \"CAR\", \"extra\": true")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("WGS84", "BD09")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_COORDINATE_SYSTEM"));
    }

    @Test
    void mapsCoordinateValidationAndNoRouteBusinessError() throws Exception {
        mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("116.39", "200.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COORDINATE"));

        when(service.plan(any())).thenThrow(new BusinessException(
                HttpStatus.CONFLICT, ErrorCode.NO_COMPLIANT_ROUTE, "未找到合规路线"));
        mvc.perform(post("/api/v1/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(header().string(RequestIdFilter.HEADER,
                        matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.code").value("NO_COMPLIANT_ROUTE"))
                .andExpect(jsonPath("$.requestId", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private static String validRequest() {
        return """
                {
                  "start": {
                    "lng": 116.39,
                    "lat": 39.90,
                    "coordinateSystem": "WGS84",
                    "source": "CURRENT_LOCATION"
                  },
                  "end": {
                    "lng": 116.41,
                    "lat": 39.91,
                    "coordinateSystem": "GCJ02",
                    "source": "MAP_PICK"
                  },
                  "vehicle": "CAR"
                }
                """;
    }
}
