package cn.camera.safe.camera;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import cn.camera.safe.routing.GeoDistance;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.List;

public final class CameraSpatialIndex {
    private final STRtree index;

    public CameraSpatialIndex(List<CameraPoint> cameras) {
        STRtree tree = new STRtree();
        cameras.forEach(camera -> tree.insert(
                new Envelope(camera.wgs84().lng(), camera.wgs84().lng(),
                        camera.wgs84().lat(), camera.wgs84().lat()),
                camera));
        tree.build();
        this.index = tree;
    }

    public List<CameraPoint> query(Envelope envelope) {
        @SuppressWarnings("unchecked")
        List<CameraPoint> candidates = (List<CameraPoint>) (List<?>) index.query(envelope);
        return List.copyOf(candidates);
    }

    public List<CameraPoint> within(Wgs84Coordinate point, double radiusMeters) {
        return query(GeoDistance.envelope(point, radiusMeters)).stream()
                .filter(camera -> GeoDistance.meters(point, camera.wgs84()) <= radiusMeters)
                .toList();
    }
}
