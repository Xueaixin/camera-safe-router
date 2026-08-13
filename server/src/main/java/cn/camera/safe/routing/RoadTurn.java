package cn.camera.safe.routing;

/**
 * A directed turn restriction from one base edge to another at a via node.
 *
 * <p>GraphHopper identifies turns by undirected base edge ids plus the shared via node,
 * so one entry covers both directions of each edge.
 */
record RoadTurn(int fromEdge, int viaNode, int toEdge) {
}
