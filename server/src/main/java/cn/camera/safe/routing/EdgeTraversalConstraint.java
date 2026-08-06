package cn.camera.safe.routing;

import com.graphhopper.util.EdgeIteratorState;

@FunctionalInterface
public interface EdgeTraversalConstraint {
    EdgeTraversalConstraint ALLOW_ALL = (edge, fractionFromBase) -> true;

    boolean allows(EdgeIteratorState edge, double fractionFromBase);

    default boolean allows(EdgeIteratorState edge) {
        return allows(edge, 1.0);
    }
}
