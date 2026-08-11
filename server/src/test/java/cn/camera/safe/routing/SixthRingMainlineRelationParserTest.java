package cn.camera.safe.routing;

import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.IntsRefEdgeIntAccess;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.storage.IntsRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SixthRingMainlineRelationParserTest {

    @Test
    void marksOnlyMotorwayMembersOfTheValidatedSixthRingRelation() {
        EncodedValue.InitializerConfig edgeConfig = new EncodedValue.InitializerConfig();
        SimpleBooleanEncodedValue identity = new SimpleBooleanEncodedValue(
                RoadIdentityEncodedValues.SIXTH_RING_MAINLINE);
        identity.init(edgeConfig);
        EncodedValue.InitializerConfig relationConfig = new EncodedValue.InitializerConfig();
        SixthRingMainlineRelationParser parser = new SixthRingMainlineRelationParser(
                identity, relationConfig);
        IntsRef relationFlags = new IntsRef(relationConfig.getRequiredInts());
        IntsRef edgeFlags = new IntsRef(edgeConfig.getRequiredInts());

        ReaderRelation relation = relation(295982, "G4501");
        parser.handleRelationTags(relationFlags, relation);
        parser.handleWayTags(
                0, new IntsRefEdgeIntAccess(edgeFlags), way(1, "motorway"), relationFlags);

        assertThat(identity.getBool(false, 0, new IntsRefEdgeIntAccess(edgeFlags))).isTrue();

        IntsRef constructionFlags = new IntsRef(edgeConfig.getRequiredInts());
        parser.handleWayTags(
                0,
                new IntsRefEdgeIntAccess(constructionFlags),
                way(2, "construction"),
                relationFlags);
        assertThat(identity.getBool(
                false, 0, new IntsRefEdgeIntAccess(constructionFlags))).isFalse();
    }

    @Test
    void rejectsNameOrRefLookalikesOutsideTheValidatedRelation() {
        EncodedValue.InitializerConfig edgeConfig = new EncodedValue.InitializerConfig();
        SimpleBooleanEncodedValue identity = new SimpleBooleanEncodedValue(
                RoadIdentityEncodedValues.SIXTH_RING_MAINLINE);
        identity.init(edgeConfig);
        EncodedValue.InitializerConfig relationConfig = new EncodedValue.InitializerConfig();
        SixthRingMainlineRelationParser parser = new SixthRingMainlineRelationParser(
                identity, relationConfig);
        IntsRef relationFlags = new IntsRef(relationConfig.getRequiredInts());
        parser.handleRelationTags(relationFlags, relation(999, "G4501"));
        IntsRef edgeFlags = new IntsRef(edgeConfig.getRequiredInts());

        parser.handleWayTags(
                0, new IntsRefEdgeIntAccess(edgeFlags), way(1, "motorway"), relationFlags);

        assertThat(identity.getBool(false, 0, new IntsRefEdgeIntAccess(edgeFlags))).isFalse();
    }

    private static ReaderRelation relation(long id, String ref) {
        ReaderRelation relation = new ReaderRelation(id);
        relation.setTag("type", "route");
        relation.setTag("route", "road");
        relation.setTag("ref", ref);
        return relation;
    }

    private static ReaderWay way(long id, String highway) {
        ReaderWay way = new ReaderWay(id);
        way.setTag("highway", highway);
        return way;
    }
}
