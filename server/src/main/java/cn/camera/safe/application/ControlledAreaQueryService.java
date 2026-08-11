package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.ControlledAreaGeometry;
import cn.camera.safe.api.model.ControlledAreaResponse;
import cn.camera.safe.api.model.OutputCoordinate;
import cn.camera.safe.config.SixthRingProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.SixthRingRoutingManager;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

@Service
public final class ControlledAreaQueryService {
    private final SixthRingRoutingManager routingManager;
    private final CoordinateConverter coordinateConverter;
    private final SixthRingProperties properties;

    public ControlledAreaQueryService(
            SixthRingRoutingManager routingManager,
            CoordinateConverter coordinateConverter,
            SixthRingProperties properties) {
        this.routingManager = routingManager;
        this.coordinateConverter = coordinateConverter;
        this.properties = properties;
    }

    public ControlledAreaResponse current() {
        if (!routingManager.isReady()) {
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.SIXTH_RING_TOPOLOGY_NOT_READY,
                    "受控区边界尚未就绪");
        }
        var boundary = routingManager.requireBoundary();
        return new ControlledAreaResponse(
                boundary.version(),
                CoordinateSystem.GCJ02,
                geometry(boundary.controlledArea()),
                routingManager.isBoundaryApprovedForProduction(),
                properties.cameraOutsideMarginMeters());
    }

    private ControlledAreaGeometry geometry(Geometry source) {
        List<List<List<OutputCoordinate>>> polygons = new ArrayList<>();
        if (source instanceof Polygon polygon) {
            polygons.add(polygon(polygon));
        } else if (source instanceof MultiPolygon multiPolygon) {
            for (int index = 0; index < multiPolygon.getNumGeometries(); index++) {
                polygons.add(polygon((Polygon) multiPolygon.getGeometryN(index)));
            }
        } else {
            throw new IllegalStateException("controlled area is not polygonal");
        }
        return new ControlledAreaGeometry("MultiPolygon", polygons);
    }

    private List<List<OutputCoordinate>> polygon(Polygon polygon) {
        List<List<OutputCoordinate>> rings = new ArrayList<>();
        rings.add(ring(polygon.getExteriorRing().getCoordinates()));
        for (int index = 0; index < polygon.getNumInteriorRing(); index++) {
            rings.add(ring(polygon.getInteriorRingN(index).getCoordinates()));
        }
        return List.copyOf(rings);
    }

    private List<OutputCoordinate> ring(Coordinate[] coordinates) {
        return java.util.Arrays.stream(coordinates)
                .map(coordinate -> coordinateConverter.toGcj02(
                        new Wgs84Coordinate(coordinate.x, coordinate.y)))
                .map(coordinate -> new OutputCoordinate(coordinate.lng(), coordinate.lat()))
                .toList();
    }
}
