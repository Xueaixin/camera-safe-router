package cn.camera.safe.routing;

record RoadClassification(
        RoadClassificationIndex index,
        RoadClassificationAudit audit,
        TollCorridorTopology tollCorridors,
        HighwayInterchangeTopology interchangeTopology) {
}
