package cn.camera.safe.camera;

public record CameraValidationIssue(int recordIndex, String id, String reason) {
}
