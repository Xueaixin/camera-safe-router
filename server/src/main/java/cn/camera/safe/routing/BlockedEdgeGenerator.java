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
        BitSet forward = new BitSet();
        BitSet reverse = new BitSet();
        List<String> unmatched = new ArrayList<>();
        int matched = 0;

        for (CameraPoint camera : cameraSnapshot.cameras()) {
            boolean cameraMatched = false;
            for (RoadEdgeIndex.RoadEdgeRef edge : roadIndex.candidates(
                    camera.wgs84(), safetyRadiusMeters)) {
                if (GeoDistance.minimumMeters(camera.wgs84(), roadIndex.geometry(edge))
                        <= safetyRadiusMeters) {
                    forward.set(edge.edgeId());
                    reverse.set(edge.edgeId());
                    cameraMatched = true;
                }
            }
            if (cameraMatched) {
                matched++;
            } else {
                unmatched.add(camera.id());
            }
        }

        StringBuilder versionMaterial = new StringBuilder(cameraSnapshot.sourceSha256())
                .append('|').append(roadIndex.graphFingerprint())
                .append("|radius=").append(safetyRadiusMeters)
                .append("|edges=");
        forward.stream().forEach(edgeId -> versionMaterial.append(edgeId).append(','));
        String blockedVersion = "sha256:" + Hashing.sha256(versionMaterial.toString());
        BlockedEdgeSnapshot blocked = new BlockedEdgeSnapshot(
                forward, reverse, cameraSnapshot.version(), blockedVersion);
        return new BlockedEdgeBuildResult(blocked, matched, unmatched);
    }
}
