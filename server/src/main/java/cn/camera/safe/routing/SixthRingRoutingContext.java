package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.Weighting;

record SixthRingRoutingContext(
        SixthRingBoundary boundary,
        SixthRingPortalTopology topology,
        Weighting distanceWeighting,
        boolean approvedForProduction) {
}
