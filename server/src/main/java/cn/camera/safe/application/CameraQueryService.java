package cn.camera.safe.application;

import cn.camera.safe.api.BusinessException;
import cn.camera.safe.api.ErrorCode;
import cn.camera.safe.api.model.CameraPage;
import cn.camera.safe.api.model.CameraView;
import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.config.AppProperties;
import cn.camera.safe.coordinate.CoordinateConverter;
import cn.camera.safe.coordinate.CoordinateSystem;
import cn.camera.safe.coordinate.Gcj02Coordinate;
import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.RoutingSnapshot;
import cn.camera.safe.routing.RoutingSnapshotManager;
import org.locationtech.jts.geom.Envelope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public final class CameraQueryService {
    private final RoutingSnapshotManager snapshotManager;
    private final CoordinateConverter coordinateConverter;
    private final AppProperties properties;

    public CameraQueryService(
            RoutingSnapshotManager snapshotManager,
            CoordinateConverter coordinateConverter,
            AppProperties properties) {
        this.snapshotManager = snapshotManager;
        this.coordinateConverter = coordinateConverter;
        this.properties = properties;
    }

    public CameraPage query(
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            CoordinateSystem coordinateSystem) {
        validateBounds(minLng, minLat, maxLng, maxLat);
        RoutingSnapshot snapshot = snapshotManager.current().orElseThrow(() ->
                new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorCode.CAMERA_SNAPSHOT_NOT_READY, "摄像头快照尚未就绪"));

        Envelope wgsEnvelope = toWgsEnvelope(
                minLng, minLat, maxLng, maxLat, coordinateSystem);
        List<CameraPoint> matches = snapshot.cameraIndex().query(wgsEnvelope).stream()
                .filter(camera -> inRequestedBounds(
                        camera, minLng, minLat, maxLng, maxLat, coordinateSystem))
                .sorted(Comparator.comparing(CameraPoint::id))
                .limit(properties.cameras().maxBboxResults() + 1L)
                .toList();
        if (matches.size() > properties.cameras().maxBboxResults()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_REQUEST, "查询范围内点位过多，请缩小地图范围");
        }

        List<CameraView> views = matches.stream()
                .map(camera -> view(camera, coordinateSystem))
                .toList();
        return new CameraPage(coordinateSystem, snapshot.cameraSnapshot().version(), views);
    }

    private void validateBounds(double minLng, double minLat, double maxLng, double maxLat) {
        try {
            new Wgs84Coordinate(minLng, minLat);
            new Wgs84Coordinate(maxLng, maxLat);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_COORDINATE, "查询坐标无效");
        }
        if (minLng >= maxLng || minLat >= maxLat) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_REQUEST, "查询边界顺序无效");
        }
        double maximumSpan = properties.cameras().maxBboxSpanDegrees();
        if (maxLng - minLng > maximumSpan || maxLat - minLat > maximumSpan) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ErrorCode.INVALID_REQUEST, "查询范围超过服务限制");
        }
    }

    private Envelope toWgsEnvelope(
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            CoordinateSystem coordinateSystem) {
        if (coordinateSystem == CoordinateSystem.WGS84) {
            return new Envelope(minLng, maxLng, minLat, maxLat);
        }
        Envelope envelope = new Envelope();
        for (Gcj02Coordinate corner : new Gcj02Coordinate[]{
                new Gcj02Coordinate(minLng, minLat),
                new Gcj02Coordinate(minLng, maxLat),
                new Gcj02Coordinate(maxLng, minLat),
                new Gcj02Coordinate(maxLng, maxLat)
        }) {
            Wgs84Coordinate converted = coordinateConverter.toWgs84(corner);
            envelope.expandToInclude(converted.lng(), converted.lat());
        }
        return envelope;
    }

    private static boolean inRequestedBounds(
            CameraPoint camera,
            double minLng,
            double minLat,
            double maxLng,
            double maxLat,
            CoordinateSystem coordinateSystem) {
        double lng = coordinateSystem == CoordinateSystem.WGS84
                ? camera.wgs84().lng() : camera.gcj02().lng();
        double lat = coordinateSystem == CoordinateSystem.WGS84
                ? camera.wgs84().lat() : camera.gcj02().lat();
        return lng >= minLng && lng <= maxLng && lat >= minLat && lat <= maxLat;
    }

    private static CameraView view(CameraPoint camera, CoordinateSystem coordinateSystem) {
        double lng = coordinateSystem == CoordinateSystem.WGS84
                ? camera.wgs84().lng() : camera.gcj02().lng();
        double lat = coordinateSystem == CoordinateSystem.WGS84
                ? camera.wgs84().lat() : camera.gcj02().lat();
        return new CameraView(
                camera.id(), lng, lat, camera.address(), camera.cameraType(), camera.directionText());
    }
}
