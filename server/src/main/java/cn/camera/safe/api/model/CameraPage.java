package cn.camera.safe.api.model;

import cn.camera.safe.coordinate.CoordinateSystem;

import java.util.List;

public record CameraPage(
        CoordinateSystem coordinateSystem,
        String snapshotVersion,
        List<CameraView> items) {
    public CameraPage {
        items = List.copyOf(items);
    }
}
