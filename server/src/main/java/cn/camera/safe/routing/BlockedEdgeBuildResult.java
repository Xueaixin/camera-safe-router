package cn.camera.safe.routing;

import java.util.List;

public record BlockedEdgeBuildResult(
        BlockedEdgeSnapshot snapshot,
        int matchedCameraCount,
        List<String> unmatchedCameraIds) {

    public BlockedEdgeBuildResult {
        unmatchedCameraIds = List.copyOf(unmatchedCameraIds);
    }
}
