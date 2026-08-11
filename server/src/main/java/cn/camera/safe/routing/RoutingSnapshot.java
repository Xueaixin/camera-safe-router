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
        List<String> unmatchedCameraIds,
        int restrictedCameraCount,
        int outsideControlAreaCameraCount,
        String controlBoundaryVersion,
        double cameraOutsideMarginMeters,
        int highwayExemptCameraCount,
        RoadClassificationIndex roadClassification) {

    public RoutingSnapshot {
        unmatchedCameraIds = List.copyOf(unmatchedCameraIds);
        if (restrictedCameraCount < 0 || outsideControlAreaCameraCount < 0) {
            throw new IllegalArgumentException("camera scope counts must be non-negative");
        }
        if (restrictedCameraCount + outsideControlAreaCameraCount
                != cameraSnapshot.cameras().size()) {
            throw new IllegalArgumentException("camera scope counts must cover retained cameras");
        }
        if (controlBoundaryVersion == null || controlBoundaryVersion.isBlank()) {
            throw new IllegalArgumentException("control boundary version is required");
        }
        if (!Double.isFinite(cameraOutsideMarginMeters) || cameraOutsideMarginMeters < 0) {
            throw new IllegalArgumentException("camera outside margin must be non-negative");
        }
        if (highwayExemptCameraCount < 0 || highwayExemptCameraCount > matchedCameraCount) {
            throw new IllegalArgumentException("highway exempt camera count is invalid");
        }
        if (roadClassification == null) {
            throw new IllegalArgumentException("road classification is required");
        }
    }

    public RoutingSnapshot(
            CameraSnapshot cameraSnapshot,
            CameraSpatialIndex cameraIndex,
            BlockedEdgeSnapshot blockedEdges,
            String graphFingerprint,
            double safetyRadiusMeters,
            int matchedCameraCount,
            List<String> unmatchedCameraIds,
            int restrictedCameraCount,
            int outsideControlAreaCameraCount,
            String controlBoundaryVersion,
            double cameraOutsideMarginMeters,
            int highwayExemptCameraCount) {
        this(cameraSnapshot, cameraIndex, blockedEdges, graphFingerprint,
                safetyRadiusMeters, matchedCameraCount, unmatchedCameraIds,
                restrictedCameraCount, outsideControlAreaCameraCount,
                controlBoundaryVersion, cameraOutsideMarginMeters,
                highwayExemptCameraCount,
                RoadClassificationIndex.empty(graphFingerprint));
    }

    public RoutingSnapshot(
            CameraSnapshot cameraSnapshot,
            CameraSpatialIndex cameraIndex,
            BlockedEdgeSnapshot blockedEdges,
            String graphFingerprint,
            double safetyRadiusMeters,
            int matchedCameraCount,
            List<String> unmatchedCameraIds) {
        this(cameraSnapshot, cameraIndex, blockedEdges, graphFingerprint,
                safetyRadiusMeters, matchedCameraCount, unmatchedCameraIds,
                cameraSnapshot.cameras().size(), 0, "legacy-unscoped", 0, 0,
                RoadClassificationIndex.empty(graphFingerprint));
    }
}
