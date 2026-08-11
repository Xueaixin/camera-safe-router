package cn.camera.safe.routing;

/** Derives the POI search radius from the selected navigation handoff clearance. */
public final class ExternalHandoffSelector {
    private static final int MAX_POI_RADIUS_METERS = 200;
    private static final int CLEARANCE_MARGIN_METERS = 25;

    public ExternalHandoffPoint select(NavigationHandoffPoint selected) {
        if (selected.type() == NavigationHandoffPoint.Type.HIGHWAY) {
            return new ExternalHandoffPoint(
                    selected.coordinate(), selected.boundaryClearanceMeters(), 0);
        }
        int radius = (int) Math.floor(Math.min(
                MAX_POI_RADIUS_METERS,
                Math.max(0, selected.boundaryClearanceMeters() - CLEARANCE_MARGIN_METERS)));
        return new ExternalHandoffPoint(
                selected.coordinate(), selected.boundaryClearanceMeters(), radius);
    }
}
