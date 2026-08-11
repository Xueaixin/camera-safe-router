package cn.camera.safe.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EnumEncodedValue;
import com.graphhopper.routing.ev.IntEncodedValue;
import com.graphhopper.routing.ev.OSMWayID;
import com.graphhopper.routing.ev.RoadClass;
import com.graphhopper.routing.ev.RoadClassLink;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import static org.assertj.core.api.Assertions.assertThat;

class RoadClassifierTest {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @Test
    void auditsRelationBackedSixthRingAndGeometryBackedTongzhouMotorways() {
        BooleanEncodedValue access = VehicleAccess.create("car");
        EnumEncodedValue<RoadClass> roadClass = RoadClass.create();
        BooleanEncodedValue link = RoadClassLink.create();
        IntEncodedValue wayId = OSMWayID.create();
        BooleanEncodedValue sixthRing = new SimpleBooleanEncodedValue(
                RoadIdentityEncodedValues.SIXTH_RING_MAINLINE);
        EncodingManager encodingManager = EncodingManager.start()
                .add(access)
                .add(roadClass)
                .add(link)
                .add(wayId)
                .add(sixthRing)
                .build();
        BaseGraph graph = new BaseGraph.Builder(encodingManager).create();

        node(graph, 0, -2, 0);
        node(graph, 1, -1, 0);
        node(graph, 2, 0.2, 0.2);
        node(graph, 3, 0.8, 0.2);
        node(graph, 4, 0.2, 0.4);
        node(graph, 5, 0.8, 0.4);
        node(graph, 6, -2, 0.2);
        node(graph, 7, -1, 0.2);
        EdgeIteratorState sixth = edge(graph, 0, 1, access, roadClass, link, wayId,
                RoadClass.MOTORWAY, false, 1001);
        sixth.set(sixthRing, true);
        edge(graph, 2, 3, access, roadClass, link, wayId,
                RoadClass.MOTORWAY, false, 2001);
        edge(graph, 4, 5, access, roadClass, link, wayId,
                RoadClass.MOTORWAY, true, 2002);
        EdgeIteratorState forbiddenInterior = edge(
                graph, 6, 7, access, roadClass, link, wayId,
                RoadClass.MOTORWAY, false, 3001);

        Polygon sixthArea = square(-3, -1, -0.5, 1);
        Polygon tongzhouArea = square(0, 0, 1, 1);
        RoadClassification classification = RoadClassifier.classify(
                graph, encodingManager, sixthArea.union(tongzhouArea),
                sixthArea, tongzhouArea);
        RoadClassificationAudit audit = classification.audit();

        audit.validate();
        assertThat(audit.sixthRingMainlineWays()).isEqualTo(1);
        assertThat(audit.sixthRingDrivableDirections()).isEqualTo(2);
        assertThat(audit.tongzhouMotorwayMainlineWays()).isEqualTo(1);
        assertThat(audit.tongzhouMotorwayDrivableDirections()).isEqualTo(2);
        assertThat(audit.tongzhouMotorwayLinkEdges()).isEqualTo(1);
        assertThat(classification.index().isHighwayMainline(sixth.getEdge())).isTrue();
        assertThat(classification.index().highwayMainlineEdgeCount()).isEqualTo(2);
        assertThat(classification.index().allHighwayMainlineEdgeCount()).isEqualTo(3);
        assertThat(classification.index().isForbiddenSixthInteriorHighway(
                forbiddenInterior.getEdge())).isTrue();
        assertThat(classification.index().motorwayLinkEdgeCount()).isEqualTo(1);
    }

    private static EdgeIteratorState edge(
            BaseGraph graph,
            int base,
            int adjacent,
            BooleanEncodedValue access,
            EnumEncodedValue<RoadClass> roadClass,
            BooleanEncodedValue link,
            IntEncodedValue wayId,
            RoadClass type,
            boolean isLink,
            int osmWayId) {
        return graph.edge(base, adjacent)
                .setDistance(100)
                .set(access, true, true)
                .set(roadClass, type)
                .set(link, isLink)
                .set(wayId, osmWayId);
    }

    private static void node(BaseGraph graph, int node, double lng, double lat) {
        graph.getNodeAccess().setNode(node, lat, lng);
    }

    private static Polygon square(
            double minLng,
            double minLat,
            double maxLng,
            double maxLat) {
        return GEOMETRY_FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(minLng, minLat),
                new Coordinate(maxLng, minLat),
                new Coordinate(maxLng, maxLat),
                new Coordinate(minLng, maxLat),
                new Coordinate(minLng, minLat)
        });
    }
}
