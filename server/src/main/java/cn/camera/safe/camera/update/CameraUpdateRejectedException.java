package cn.camera.safe.camera.update;

final class CameraUpdateRejectedException extends RuntimeException {
    CameraUpdateRejectedException(String message) {
        super(message);
    }
}
