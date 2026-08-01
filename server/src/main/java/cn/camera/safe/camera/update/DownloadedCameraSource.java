package cn.camera.safe.camera.update;

public record DownloadedCameraSource(byte[] content, String sha256) {
    public DownloadedCameraSource {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
