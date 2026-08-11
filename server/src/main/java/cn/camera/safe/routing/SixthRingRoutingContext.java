package cn.camera.safe.routing;

import com.graphhopper.routing.weighting.Weighting;

record SixthRingRoutingContext(
        SixthRingBoundary boundary,
        SixthRingPortalTopology topology,
        ControlReleaseTopology releaseTopology,
        Weighting routingWeighting,
        Weighting controlledTimeWeighting,
        RoadClassificationIndex roadClassification,
        RoadClassificationAudit roadClassificationAudit,
        TollCorridorTopology tollCorridors,
        HighwayInterchangeTopology interchangeTopology,
        boolean approvedForProduction) {
}
