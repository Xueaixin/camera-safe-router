package cn.camera.safe.routing;

import com.graphhopper.util.GHUtility;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/** Immutable road identities derived from the loaded base graph and controlled areas. */
public final class RoadClassificationIndex {
    private final BitSet cameraExemptMainlineEdges;
    private final BitSet allHighwayMainlineEdges;
    private final BitSet sixthRingMainlineEdges;
    private final BitSet tongzhouHighwayMainlineEdges;
    private final BitSet forbiddenSixthInteriorHighwayEdges;
    private final BitSet motorwayLinkEdges;
    private final BitSet sixthInteriorEdges;
    private final BitSet tongzhouOutsideSixthEdges;
    private final BitSet releasedConnectorEdges;
    private final BitSet sixthExitConnectorEdgeKeys;
    private final BitSet sixthTollEntryEdgeKeys;
    private final BitSet sixthTollExitEdgeKeys;
    private final BitSet tongzhouHighwayConnectorEdges;
    private final BitSet rhtEntryEdgeKeys;
    private final BitSet rhtExitEdgeKeys;
    private final BitSet tongzhouCheckpointBypassEdges;
    private final List<RoadTurn> forbiddenDirectAccessTurns;
    private final String fingerprint;

    /** Compatibility constructor for focused tests and legacy routing modes. */
    public RoadClassificationIndex(
            BitSet highwayMainlineEdges,
            BitSet motorwayLinkEdges,
            BitSet releasedConnectorEdges,
            String fingerprint) {
        this(
                highwayMainlineEdges,
                highwayMainlineEdges,
                highwayMainlineEdges,
                new BitSet(),
                new BitSet(),
                motorwayLinkEdges,
                new BitSet(),
                new BitSet(),
                releasedConnectorEdges,
                directedKeys(releasedConnectorEdges),
                new BitSet(),
                directedKeys(releasedConnectorEdges),
                releasedConnectorEdges,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                fingerprint);
    }

    public RoadClassificationIndex(
            BitSet cameraExemptMainlineEdges,
            BitSet allHighwayMainlineEdges,
            BitSet sixthRingMainlineEdges,
            BitSet tongzhouHighwayMainlineEdges,
            BitSet forbiddenSixthInteriorHighwayEdges,
            BitSet motorwayLinkEdges,
            BitSet sixthInteriorEdges,
            BitSet tongzhouOutsideSixthEdges,
            BitSet releasedConnectorEdges,
            BitSet sixthExitConnectorEdgeKeys,
            BitSet tongzhouHighwayConnectorEdges,
            String fingerprint) {
        this(
                cameraExemptMainlineEdges,
                allHighwayMainlineEdges,
                sixthRingMainlineEdges,
                tongzhouHighwayMainlineEdges,
                forbiddenSixthInteriorHighwayEdges,
                motorwayLinkEdges,
                sixthInteriorEdges,
                tongzhouOutsideSixthEdges,
                releasedConnectorEdges,
                sixthExitConnectorEdgeKeys,
                new BitSet(),
                sixthExitConnectorEdgeKeys,
                tongzhouHighwayConnectorEdges,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                fingerprint);
    }

    public RoadClassificationIndex(
            BitSet cameraExemptMainlineEdges,
            BitSet allHighwayMainlineEdges,
            BitSet sixthRingMainlineEdges,
            BitSet tongzhouHighwayMainlineEdges,
            BitSet forbiddenSixthInteriorHighwayEdges,
            BitSet motorwayLinkEdges,
            BitSet sixthInteriorEdges,
            BitSet tongzhouOutsideSixthEdges,
            BitSet releasedConnectorEdges,
            BitSet sixthExitConnectorEdgeKeys,
            BitSet sixthTollEntryEdgeKeys,
            BitSet sixthTollExitEdgeKeys,
            BitSet tongzhouHighwayConnectorEdges,
            String fingerprint) {
        this(
                cameraExemptMainlineEdges,
                allHighwayMainlineEdges,
                sixthRingMainlineEdges,
                tongzhouHighwayMainlineEdges,
                forbiddenSixthInteriorHighwayEdges,
                motorwayLinkEdges,
                sixthInteriorEdges,
                tongzhouOutsideSixthEdges,
                releasedConnectorEdges,
                sixthExitConnectorEdgeKeys,
                sixthTollEntryEdgeKeys,
                sixthTollExitEdgeKeys,
                tongzhouHighwayConnectorEdges,
                new BitSet(),
                new BitSet(),
                new BitSet(),
                fingerprint);
    }

    public RoadClassificationIndex(
            BitSet cameraExemptMainlineEdges,
            BitSet allHighwayMainlineEdges,
            BitSet sixthRingMainlineEdges,
            BitSet tongzhouHighwayMainlineEdges,
            BitSet forbiddenSixthInteriorHighwayEdges,
            BitSet motorwayLinkEdges,
            BitSet sixthInteriorEdges,
            BitSet tongzhouOutsideSixthEdges,
            BitSet releasedConnectorEdges,
            BitSet sixthExitConnectorEdgeKeys,
            BitSet sixthTollEntryEdgeKeys,
            BitSet sixthTollExitEdgeKeys,
            BitSet tongzhouHighwayConnectorEdges,
            BitSet rhtEntryEdgeKeys,
            BitSet rhtExitEdgeKeys,
            BitSet tongzhouCheckpointBypassEdges,
            List<RoadTurn> forbiddenDirectAccessTurns,
            String fingerprint) {
        this.cameraExemptMainlineEdges = copy(
                cameraExemptMainlineEdges, "cameraExemptMainlineEdges");
        this.allHighwayMainlineEdges = copy(allHighwayMainlineEdges, "allHighwayMainlineEdges");
        this.sixthRingMainlineEdges = copy(sixthRingMainlineEdges, "sixthRingMainlineEdges");
        this.tongzhouHighwayMainlineEdges = copy(
                tongzhouHighwayMainlineEdges, "tongzhouHighwayMainlineEdges");
        this.forbiddenSixthInteriorHighwayEdges = copy(
                forbiddenSixthInteriorHighwayEdges, "forbiddenSixthInteriorHighwayEdges");
        this.motorwayLinkEdges = copy(motorwayLinkEdges, "motorwayLinkEdges");
        this.sixthInteriorEdges = copy(sixthInteriorEdges, "sixthInteriorEdges");
        this.tongzhouOutsideSixthEdges = copy(
                tongzhouOutsideSixthEdges, "tongzhouOutsideSixthEdges");
        this.releasedConnectorEdges = copy(releasedConnectorEdges, "releasedConnectorEdges");
        this.sixthExitConnectorEdgeKeys = copy(
                sixthExitConnectorEdgeKeys, "sixthExitConnectorEdgeKeys");
        this.sixthTollEntryEdgeKeys = copy(
                sixthTollEntryEdgeKeys, "sixthTollEntryEdgeKeys");
        this.sixthTollExitEdgeKeys = copy(
                sixthTollExitEdgeKeys, "sixthTollExitEdgeKeys");
        this.tongzhouHighwayConnectorEdges = copy(
                tongzhouHighwayConnectorEdges, "tongzhouHighwayConnectorEdges");
        this.rhtEntryEdgeKeys = copy(rhtEntryEdgeKeys, "rhtEntryEdgeKeys");
        this.rhtExitEdgeKeys = copy(rhtExitEdgeKeys, "rhtExitEdgeKeys");
        this.tongzhouCheckpointBypassEdges = copy(
                tongzhouCheckpointBypassEdges, "tongzhouCheckpointBypassEdges");
        this.forbiddenDirectAccessTurns = List.copyOf(forbiddenDirectAccessTurns);
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("road classification fingerprint is required");
        }
        this.fingerprint = fingerprint;
    }

    /** Compatibility constructor that keeps the illegal-direct-access turn set empty. */
    public RoadClassificationIndex(
            BitSet cameraExemptMainlineEdges,
            BitSet allHighwayMainlineEdges,
            BitSet sixthRingMainlineEdges,
            BitSet tongzhouHighwayMainlineEdges,
            BitSet forbiddenSixthInteriorHighwayEdges,
            BitSet motorwayLinkEdges,
            BitSet sixthInteriorEdges,
            BitSet tongzhouOutsideSixthEdges,
            BitSet releasedConnectorEdges,
            BitSet sixthExitConnectorEdgeKeys,
            BitSet sixthTollEntryEdgeKeys,
            BitSet sixthTollExitEdgeKeys,
            BitSet tongzhouHighwayConnectorEdges,
            BitSet rhtEntryEdgeKeys,
            BitSet rhtExitEdgeKeys,
            BitSet tongzhouCheckpointBypassEdges,
            String fingerprint) {
        this(
                cameraExemptMainlineEdges,
                allHighwayMainlineEdges,
                sixthRingMainlineEdges,
                tongzhouHighwayMainlineEdges,
                forbiddenSixthInteriorHighwayEdges,
                motorwayLinkEdges,
                sixthInteriorEdges,
                tongzhouOutsideSixthEdges,
                releasedConnectorEdges,
                sixthExitConnectorEdgeKeys,
                sixthTollEntryEdgeKeys,
                sixthTollExitEdgeKeys,
                tongzhouHighwayConnectorEdges,
                rhtEntryEdgeKeys,
                rhtExitEdgeKeys,
                tongzhouCheckpointBypassEdges,
                List.of(),
                fingerprint);
    }

    static RoadClassificationIndex empty(String graphFingerprint) {
        BitSet empty = new BitSet();
        return new RoadClassificationIndex(
                empty, empty, empty, empty, empty, empty,
                empty, empty, empty, empty, empty, empty, empty, empty, empty,
                empty,
                "empty:" + graphFingerprint);
    }

    /** Returns true only for mainlines explicitly exempt from camera restrictions. */
    public boolean isHighwayMainline(int baseEdgeId) {
        return contains(cameraExemptMainlineEdges, baseEdgeId);
    }

    public boolean isAnyHighwayMainline(int baseEdgeId) {
        return contains(allHighwayMainlineEdges, baseEdgeId);
    }

    public boolean isSixthRingMainline(int baseEdgeId) {
        return contains(sixthRingMainlineEdges, baseEdgeId);
    }

    public boolean isTongzhouHighwayMainline(int baseEdgeId) {
        return contains(tongzhouHighwayMainlineEdges, baseEdgeId);
    }

    public boolean isForbiddenSixthInteriorHighway(int baseEdgeId) {
        return contains(forbiddenSixthInteriorHighwayEdges, baseEdgeId);
    }

    public boolean isMotorwayLink(int baseEdgeId) {
        return contains(motorwayLinkEdges, baseEdgeId);
    }

    public boolean isSixthInterior(int baseEdgeId) {
        return contains(sixthInteriorEdges, baseEdgeId);
    }

    public boolean isTongzhouOutsideSixth(int baseEdgeId) {
        return contains(tongzhouOutsideSixthEdges, baseEdgeId);
    }

    public boolean isReleasedConnector(int baseEdgeId) {
        return contains(releasedConnectorEdges, baseEdgeId);
    }

    public boolean isSixthExitConnectorEdgeKey(int directedEdgeKey) {
        return directedEdgeKey >= 0 && sixthExitConnectorEdgeKeys.get(directedEdgeKey);
    }

    public boolean isSixthTollEntryEdgeKey(int directedEdgeKey) {
        return directedEdgeKey >= 0 && sixthTollEntryEdgeKeys.get(directedEdgeKey);
    }

    public boolean isSixthTollExitEdgeKey(int directedEdgeKey) {
        return directedEdgeKey >= 0 && sixthTollExitEdgeKeys.get(directedEdgeKey);
    }

    public boolean isSixthTollCorridorEdgeKey(int directedEdgeKey) {
        return isSixthTollEntryEdgeKey(directedEdgeKey)
                || isSixthTollExitEdgeKey(directedEdgeKey);
    }

    public boolean isTongzhouHighwayConnector(int baseEdgeId) {
        return contains(tongzhouHighwayConnectorEdges, baseEdgeId);
    }

    /** 通州境内高速检查站（主路 access=no）的平行绕行辅路，released 阶段允许通行。 */
    public boolean isTongzhouCheckpointBypass(int baseEdgeId) {
        return contains(tongzhouCheckpointBypassEdges, baseEdgeId);
    }

    /** Directed ordinary-to-mainline / mainline-to-ordinary turns forbidden at through-nodes. */
    public List<RoadTurn> forbiddenDirectAccessTurns() {
        return forbiddenDirectAccessTurns;
    }

    public int forbiddenDirectAccessTurnCount() {
        return forbiddenDirectAccessTurns.size();
    }

    /** Directed link-chain keys of verified {@code H-T -> R} interchange corridors. */
    public boolean isRhtEntryEdgeKey(int directedEdgeKey) {
        return directedEdgeKey >= 0 && rhtEntryEdgeKeys.get(directedEdgeKey);
    }

    /** Directed link-chain keys of verified {@code R -> H-T} interchange corridors. */
    public boolean isRhtExitEdgeKey(int directedEdgeKey) {
        return directedEdgeKey >= 0 && rhtExitEdgeKeys.get(directedEdgeKey);
    }

    public boolean isRhtCorridorEdgeKey(int directedEdgeKey) {
        return isRhtEntryEdgeKey(directedEdgeKey) || isRhtExitEdgeKey(directedEdgeKey);
    }

    public int highwayMainlineEdgeCount() {
        return cameraExemptMainlineEdges.cardinality();
    }

    public int allHighwayMainlineEdgeCount() {
        return allHighwayMainlineEdges.cardinality();
    }

    public int motorwayLinkEdgeCount() {
        return motorwayLinkEdges.cardinality();
    }

    public int forbiddenSixthInteriorHighwayEdgeCount() {
        return forbiddenSixthInteriorHighwayEdges.cardinality();
    }

    public int releasedConnectorEdgeCount() {
        return releasedConnectorEdges.cardinality();
    }

    public int sixthTollEntryEdgeKeyCount() {
        return sixthTollEntryEdgeKeys.cardinality();
    }

    public int sixthTollExitEdgeKeyCount() {
        return sixthTollExitEdgeKeys.cardinality();
    }

    public int rhtEntryEdgeKeyCount() {
        return rhtEntryEdgeKeys.cardinality();
    }

    public int rhtExitEdgeKeyCount() {
        return rhtExitEdgeKeys.cardinality();
    }

    public String fingerprint() {
        return fingerprint;
    }

    private static boolean contains(BitSet values, int index) {
        return index >= 0 && values.get(index);
    }

    private static BitSet copy(BitSet values, String name) {
        return (BitSet) Objects.requireNonNull(values, name).clone();
    }

    private static BitSet directedKeys(BitSet baseEdges) {
        BitSet keys = new BitSet();
        baseEdges.stream().forEach(edgeId -> {
            keys.set(edgeId * 2);
            keys.set(GHUtility.reverseEdgeKey(edgeId * 2));
        });
        return keys;
    }
}
