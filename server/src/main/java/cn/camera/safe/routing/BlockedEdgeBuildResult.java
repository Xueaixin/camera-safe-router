package cn.camera.safe.routing;

import java.util.List;

public record BlockedEdgeBuildResult(
        BlockedEdgeSnapshot snapshot,
        int matchedCameraCount,
        int highwayExemptCameraCount,
        List<String> unmatchedCameraIds) {

    public BlockedEdgeBuildResult {
        unmatchedCameraIds = List.copyOf(unmatchedCameraIds);
        if (matchedCameraCount < 0 || highwayExemptCameraCount < 0
                || highwayExemptCameraCount > matchedCameraCount) {
            throw new IllegalArgumentException("camera match counts are invalid");
        }
    }
}
