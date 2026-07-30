package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraSnapshot;
import cn.camera.safe.camera.CameraSpatialIndex;

import java.util.List;

public record RoutingSnapshot(
        CameraSnapshot cameraSnapshot,
        CameraSpatialIndex cameraIndex,
        BlockedEdgeSnapshot blockedEdges,
        String graphFingerprint,
        double safetyRadiusMeters,
        int matchedCameraCount,
        List<String> unmatchedCameraIds) {

    public RoutingSnapshot {
        unmatchedCameraIds = List.copyOf(unmatchedCameraIds);
    }
}
