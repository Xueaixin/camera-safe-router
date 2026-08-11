package cn.camera.safe.routing;

import cn.camera.safe.camera.CameraPoint;
import cn.camera.safe.camera.CameraSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

@Component
public final class BlockedEdgeGenerator {
    public BlockedEdgeBuildResult generate(
            CameraSnapshot cameraSnapshot,
            RoadEdgeIndex roadIndex,
            double safetyRadiusMeters) {
        return generate(cameraSnapshot, cameraSnapshot.cameras(), roadIndex,
                safetyRadiusMeters, "legacy-unscoped", 0,
                RoadClassificationIndex.empty(roadIndex.graphFingerprint()));
    }

    public BlockedEdgeBuildResult generate(
            CameraSnapshot cameraSnapshot,
            List<CameraPoint> restrictedCameras,
            RoadEdgeIndex roadIndex,
            double safetyRadiusMeters,
            String controlBoundaryVersion,
            double cameraOutsideMarginMeters) {
        return generate(
                cameraSnapshot,
                restrictedCameras,
                roadIndex,
                safetyRadiusMeters,
                controlBoundaryVersion,
                cameraOutsideMarginMeters,
                RoadClassificationIndex.empty(roadIndex.graphFingerprint()));
    }

    public BlockedEdgeBuildResult generate(
            CameraSnapshot cameraSnapshot,
            List<CameraPoint> restrictedCameras,
            RoadEdgeIndex roadIndex,
            double safetyRadiusMeters,
            String controlBoundaryVersion,
            double cameraOutsideMarginMeters,
            RoadClassificationIndex roadClassification) {
        BitSet forward = new BitSet();
        BitSet reverse = new BitSet();
        List<String> unmatched = new ArrayList<>();
        int matched = 0;
        int highwayExempt = 0;

        for (CameraPoint camera : restrictedCameras) {
            boolean cameraMatched = false;
            boolean blockedNonHighway = false;
            boolean matchedHighway = false;
            for (RoadEdgeIndex.RoadEdgeRef edge : roadIndex.candidates(
                    camera.wgs84(), safetyRadiusMeters)) {
                if (GeoDistance.minimumMeters(camera.wgs84(), roadIndex.geometry(edge))
                        <= safetyRadiusMeters) {
                    cameraMatched = true;
                    if (roadClassification.isHighwayMainline(edge.edgeId())) {
                        matchedHighway = true;
                        continue;
                    }
                    forward.set(edge.edgeId());
                    reverse.set(edge.edgeId());
                    blockedNonHighway = true;
                }
            }
            if (cameraMatched) {
                matched++;
                if (matchedHighway && !blockedNonHighway) {
                    highwayExempt++;
                }
            } else {
                unmatched.add(camera.id());
            }
        }

        StringBuilder versionMaterial = new StringBuilder(cameraSnapshot.sourceSha256())
                .append('|').append(roadIndex.graphFingerprint())
                .append("|radius=").append(safetyRadiusMeters)
                .append("|boundary=").append(controlBoundaryVersion)
                .append("|outside-margin=").append(cameraOutsideMarginMeters)
                .append("|camera-policy=h-mainline-exempt-v1")
                .append("|road-classification=").append(roadClassification.fingerprint())
                .append("|edges=");
        forward.stream().forEach(edgeId -> versionMaterial.append(edgeId).append(','));
        String blockedVersion = "sha256:" + Hashing.sha256(versionMaterial.toString());
        BlockedEdgeSnapshot blocked = new BlockedEdgeSnapshot(
                forward, reverse, cameraSnapshot.version(), blockedVersion);
        return new BlockedEdgeBuildResult(blocked, matched, highwayExempt, unmatched);
    }
}
