package cn.camera.safe.routing;

import cn.camera.safe.coordinate.Wgs84Coordinate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalHandoffSelectorTest {
    private final ExternalHandoffSelector selector = new ExternalHandoffSelector();

    @Test
    void capsTheDynamicPoiRadiusAtTwoHundredMeters() {
        ExternalHandoffPoint handoff = selector.select(handoff(260));

        assertThat(handoff.poiSearchRadiusMeters()).isEqualTo(200);
    }

    @Test
    void keepsTwentyFiveMetersOfBoundaryMargin() {
        ExternalHandoffPoint handoff = selector.select(handoff(80.9));

        assertThat(handoff.poiSearchRadiusMeters()).isEqualTo(55);
    }

    @Test
    void disablesPoiSearchForHighwayHandoffs() {
        NavigationHandoffPoint selected = new NavigationHandoffPoint(
                new Wgs84Coordinate(116, 40),
                5,
                "六环高速",
                NavigationHandoffPoint.Type.HIGHWAY,
                NavigationHandoffPoint.Segment.REFERENCE,
                1,
                0.5);

        assertThat(selector.select(selected).poiSearchRadiusMeters()).isZero();
    }

    private static NavigationHandoffPoint handoff(double clearance) {
        return new NavigationHandoffPoint(
                new Wgs84Coordinate(116, 40),
                clearance,
                "普通道路",
                NavigationHandoffPoint.Segment.REFERENCE,
                1);
    }
}
