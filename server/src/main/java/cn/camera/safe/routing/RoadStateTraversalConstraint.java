package cn.camera.safe.routing;

import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import org.locationtech.jts.geom.Geometry;

import java.util.Objects;

/** Search-time road-state constraint for the controlled and released route phases. */
public final class RoadStateTraversalConstraint implements EdgeTraversalConstraint {
    private final Mode mode;
    private final int baseEdgeCount;
    private final RoadClassificationIndex roadClassification;
    private final BoundaryTraversalConstraint spatialConstraint;

    private RoadStateTraversalConstraint(
            Mode mode,
            Geometry controlledArea,
            int baseEdgeCount,
            RoadClassificationIndex roadClassification) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.baseEdgeCount = baseEdgeCount;
        this.roadClassification = Objects.requireNonNull(
                roadClassification, "roadClassification");
        this.spatialConstraint = mode == Mode.CONTROLLED
                ? BoundaryTraversalConstraint.stayWithin(controlledArea, baseEdgeCount)
                : BoundaryTraversalConstraint.avoidInterior(controlledArea, baseEdgeCount);
    }

    public static RoadStateTraversalConstraint controlled(
            Geometry controlledArea,
            int baseEdgeCount,
            RoadClassificationIndex roadClassification) {
        return new RoadStateTraversalConstraint(
                Mode.CONTROLLED, controlledArea, baseEdgeCount, roadClassification);
    }

    public static RoadStateTraversalConstraint released(
            Geometry controlledArea,
            int baseEdgeCount,
            RoadClassificationIndex roadClassification) {
        return new RoadStateTraversalConstraint(
                Mode.RELEASED, controlledArea, baseEdgeCount, roadClassification);
    }

    @Override
    public boolean allows(EdgeIteratorState edge, double fractionFromBase) {
        return allows(edge, false, fractionFromBase);
    }

    @Override
    public boolean allows(
            EdgeIteratorState edge,
            boolean reverse,
            double fractionFromBase) {
        Objects.requireNonNull(edge, "edge");
        if (!Double.isFinite(fractionFromBase)
                || fractionFromBase < 0 || fractionFromBase > 1) {
            throw new IllegalArgumentException("edge fraction must be within [0, 1]");
        }
        int baseEdgeId = OriginalEdgeKey.baseEdgeId(edge, baseEdgeCount);
        if (mode == Mode.CONTROLLED) {
            if (roadClassification.isAnyHighwayMainline(baseEdgeId)
                    || roadClassification.isMotorwayLink(baseEdgeId)) {
                return false;
            }
            return spatialConstraint.allows(edge, fractionFromBase);
        }
        if (roadClassification.isForbiddenSixthInteriorHighway(baseEdgeId)) {
            return false;
        }
        int originalEdgeKey = OriginalEdgeKey.resolve(edge, baseEdgeCount);
        int directedEdgeKey = reverse
                ? GHUtility.reverseEdgeKey(originalEdgeKey) : originalEdgeKey;
        if (roadClassification.isSixthTollEntryEdgeKey(directedEdgeKey)
                || roadClassification.isSixthTollExitEdgeKey(directedEdgeKey)) {
            return true;
        }
        if (roadClassification.isRhtEntryEdgeKey(directedEdgeKey)
                || roadClassification.isRhtExitEdgeKey(directedEdgeKey)) {
            return true;
        }
        if (roadClassification.isAnyHighwayMainline(baseEdgeId)) {
            return true;
        }
        if (roadClassification.isMotorwayLink(baseEdgeId)) {
            if (roadClassification.isSixthInterior(baseEdgeId)) {
                return roadClassification.isSixthExitConnectorEdgeKey(directedEdgeKey);
            }
            if (roadClassification.isTongzhouOutsideSixth(baseEdgeId)) {
                return roadClassification.isTongzhouHighwayConnector(baseEdgeId);
            }
        }
        return spatialConstraint.allows(edge, fractionFromBase);
    }

    private enum Mode {
        CONTROLLED,
        RELEASED
    }
}
