package cn.camera.safe.coordinate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CoordinateConverterTest {
    private final CoordinateConverter converter = new CoordinateConverter();

    @Test
    void convertsKnownBeijingCoordinateAndInvertsWithinOneMeter() {
        Wgs84Coordinate tiananmen = new Wgs84Coordinate(116.397389, 39.908722);

        Gcj02Coordinate gcj02 = converter.toGcj02(tiananmen);
        Wgs84Coordinate restored = converter.toWgs84(gcj02);

        assertThat(gcj02.lng()).isCloseTo(116.4036325533, within(1e-7));
        assertThat(gcj02.lat()).isCloseTo(39.9101254757, within(1e-7));
        assertThat(restored.lng()).isCloseTo(tiananmen.lng(), within(1e-7));
        assertThat(restored.lat()).isCloseTo(tiananmen.lat(), within(1e-7));
    }

    @Test
    void roundTripsMultipleBeijingControlCandidatesDeterministically() {
        for (Wgs84Coordinate source : new Wgs84Coordinate[]{
                new Wgs84Coordinate(116.20, 39.75),
                new Wgs84Coordinate(116.55, 39.90),
                new Wgs84Coordinate(116.40, 40.15)
        }) {
            Wgs84Coordinate restored = converter.toWgs84(converter.toGcj02(source));
            assertThat(restored.lng()).isCloseTo(source.lng(), within(1e-7));
            assertThat(restored.lat()).isCloseTo(source.lat(), within(1e-7));
        }
    }

    @Test
    void leavesCoordinatesOutsideChinaUnchanged() {
        Wgs84Coordinate source = new Wgs84Coordinate(2.3522, 48.8566);
        assertThat(converter.toGcj02(source)).isEqualTo(new Gcj02Coordinate(source.lng(), source.lat()));
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeCoordinates() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Wgs84Coordinate(Double.NaN, 39));
        assertThatIllegalArgumentException().isThrownBy(() -> new Gcj02Coordinate(181, 39));
        assertThatIllegalArgumentException().isThrownBy(() -> new Wgs84Coordinate(116, -91));
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
