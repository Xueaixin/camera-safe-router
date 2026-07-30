package cn.camera.safe.api.model;

public record CameraView(
        String id,
        double lng,
        double lat,
        String address,
        String cameraType,
        String directionText) {
}
