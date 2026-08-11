package cn.camera.safe.routing;

record RoadClassificationAudit(
        int sixthRingRelationEdges,
        int sixthRingMainlineEdges,
        int sixthRingMainlineWays,
        int sixthRingDrivableDirections,
        int sixthRingClassificationMismatches,
        int tongzhouMotorwayMainlineEdges,
        int tongzhouMotorwayMainlineWays,
        int tongzhouMotorwayDrivableDirections,
        int tongzhouMotorwayLinkEdges) {

    void validate() {
        if (sixthRingRelationEdges <= 0 || sixthRingMainlineEdges <= 0
                || sixthRingMainlineWays <= 0 || sixthRingDrivableDirections <= 0) {
            throw new IllegalStateException("六环 r295982 主路身份未写入当前路网缓存");
        }
        if (sixthRingClassificationMismatches != 0) {
            throw new IllegalStateException("六环关系身份与 GraphHopper 主路分类不一致");
        }
        if (tongzhouMotorwayMainlineEdges <= 0 || tongzhouMotorwayMainlineWays <= 0
                || tongzhouMotorwayDrivableDirections <= 0) {
            throw new IllegalStateException("当前路网未识别出通州区高速主路");
        }
    }
}
