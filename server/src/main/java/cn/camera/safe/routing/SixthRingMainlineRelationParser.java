package cn.camera.safe.routing;

import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.EdgeIntAccess;
import com.graphhopper.routing.ev.EncodedValue;
import com.graphhopper.routing.ev.IntsRefEdgeIntAccess;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.util.parsers.RelationTagParser;
import com.graphhopper.storage.IntsRef;

final class SixthRingMainlineRelationParser implements RelationTagParser {
    static final long RELATION_ID = 295982L;

    private final BooleanEncodedValue edgeIdentity;
    private final BooleanEncodedValue relationMembership;

    SixthRingMainlineRelationParser(
            BooleanEncodedValue edgeIdentity,
            EncodedValue.InitializerConfig relationConfig) {
        this.edgeIdentity = edgeIdentity;
        this.relationMembership = new SimpleBooleanEncodedValue("sixth_ring_relation_member");
        this.relationMembership.init(relationConfig);
    }

    @Override
    public void handleRelationTags(IntsRef relationFlags, ReaderRelation relation) {
        if (relation.getId() != RELATION_ID
                || !relation.hasTag("type", "route")
                || !relation.hasTag("route", "road")
                || !relation.hasTag("ref", "G4501")) {
            return;
        }
        relationMembership.setBool(
                false, -1, new IntsRefEdgeIntAccess(relationFlags), true);
    }

    @Override
    public void handleWayTags(
            int edgeId,
            EdgeIntAccess edgeIntAccess,
            ReaderWay way,
            IntsRef relationFlags) {
        boolean member = relationMembership.getBool(
                false, -1, new IntsRefEdgeIntAccess(relationFlags));
        edgeIdentity.setBool(
                false,
                edgeId,
                edgeIntAccess,
                member && way.hasTag("highway", "motorway"));
    }
}
