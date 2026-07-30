package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.List;
import java.util.function.Predicate;

/** Spatially narrows full edge geometries before exact meter-distance checks. */
public final class RoadEdgeIndex {
    private final BaseGraph graph;
    private final STRtree index;
    private final int indexedEdgeCount;
    private final String graphFingerprint;

    private RoadEdgeIndex(BaseGraph graph, STRtree index, int indexedEdgeCount, String graphFingerprint) {
        this.graph = graph;
        this.index = index;
        this.indexedEdgeCount = indexedEdgeCount;
        this.graphFingerprint = graphFingerprint;
    }

    public static RoadEdgeIndex build(
            BaseGraph graph,
            Predicate<EdgeIteratorState> drivable,
            String graphFingerprint) {
        STRtree tree = new STRtree();
        int count = 0;
        AllEdgesIterator edges = graph.getAllEdges();
        while (edges.next()) {
            if (!drivable.test(edges)) {
                continue;
            }
            PointList geometry = edges.fetchWayGeometry(FetchMode.ALL);
            Envelope envelope = envelope(geometry);
            if (!envelope.isNull()) {
                tree.insert(envelope, new RoadEdgeRef(edges.getEdge()));
                count++;
            }
        }
        tree.build();
        return new RoadEdgeIndex(graph, tree, count, graphFingerprint);
    }

    public List<RoadEdgeRef> candidates(Wgs84Coordinate point, double radiusMeters) {
        @SuppressWarnings("unchecked")
        List<RoadEdgeRef> candidates = (List<RoadEdgeRef>) (List<?>) index.query(
                GeoDistance.envelope(point, radiusMeters));
        return candidates;
    }

    public PointList geometry(RoadEdgeRef edge) {
        return graph.getEdgeIteratorState(edge.edgeId(), Integer.MIN_VALUE)
                .fetchWayGeometry(FetchMode.ALL);
    }

    public int indexedEdgeCount() {
        return indexedEdgeCount;
    }

    public String graphFingerprint() {
        return graphFingerprint;
    }

    private static Envelope envelope(PointList geometry) {
        Envelope envelope = new Envelope();
        for (int index = 0; index < geometry.size(); index++) {
            envelope.expandToInclude(geometry.getLon(index), geometry.getLat(index));
        }
        return envelope;
    }

    public record RoadEdgeRef(int edgeId) {
    }
}
