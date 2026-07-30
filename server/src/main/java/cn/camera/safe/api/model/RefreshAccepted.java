package cn.camera.safe.api.model;

public record RefreshAccepted(String jobId, String status) {
    public RefreshAccepted {
        if (!"ACCEPTED".equals(status)) {
            throw new IllegalArgumentException("refresh status must be ACCEPTED");
        }
    }
}
