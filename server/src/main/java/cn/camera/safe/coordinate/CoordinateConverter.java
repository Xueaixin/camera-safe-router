package cn.camera.safe.coordinate;

import org.springframework.stereotype.Component;

/** Deterministic GCJ-02 transform with iterative inversion for routing inputs. */
@Component
public final class CoordinateConverter {
    private static final double SEMI_MAJOR_AXIS = 6_378_245.0;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323;
    private static final double CONVERGENCE_DEGREES = 1e-8;
    private static final int MAX_INVERSE_ITERATIONS = 30;

    public Gcj02Coordinate toGcj02(Wgs84Coordinate source) {
        if (outsideChina(source.lng(), source.lat())) {
            return new Gcj02Coordinate(source.lng(), source.lat());
        }
        Delta delta = offset(source.lng(), source.lat());
        return new Gcj02Coordinate(source.lng() + delta.lng(), source.lat() + delta.lat());
    }

    public Wgs84Coordinate toWgs84(Gcj02Coordinate source) {
        if (outsideChina(source.lng(), source.lat())) {
            return new Wgs84Coordinate(source.lng(), source.lat());
        }

        double candidateLng = source.lng();
        double candidateLat = source.lat();
        for (int iteration = 0; iteration < MAX_INVERSE_ITERATIONS; iteration++) {
            Gcj02Coordinate projected = toGcj02(new Wgs84Coordinate(candidateLng, candidateLat));
            double lngError = projected.lng() - source.lng();
            double latError = projected.lat() - source.lat();
            candidateLng -= lngError;
            candidateLat -= latError;
            if (Math.max(Math.abs(lngError), Math.abs(latError)) <= CONVERGENCE_DEGREES) {
                break;
            }
        }
        return new Wgs84Coordinate(candidateLng, candidateLat);
    }

    private static Delta offset(double lng, double lat) {
        double x = lng - 105.0;
        double y = lat - 35.0;
        double latitudeTransform = transformLatitude(x, y);
        double longitudeTransform = transformLongitude(x, y);
        double latitudeRadians = Math.toRadians(lat);
        double sinLatitude = Math.sin(latitudeRadians);
        double magic = 1 - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude;
        double sqrtMagic = Math.sqrt(magic);

        double latitudeDelta = latitudeTransform * 180.0
                / ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED))
                / (magic * sqrtMagic) * Math.PI);
        double longitudeDelta = longitudeTransform * 180.0
                / (SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(latitudeRadians) * Math.PI);
        return new Delta(longitudeDelta, latitudeDelta);
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y
                + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(y * Math.PI)
                + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        result += (160.0 * Math.sin(y / 12.0 * Math.PI)
                + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0 + x + 2.0 * y + 0.1 * x * x
                + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(x * Math.PI)
                + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        result += (150.0 * Math.sin(x / 12.0 * Math.PI)
                + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return result;
    }

    private static boolean outsideChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private record Delta(double lng, double lat) {
    }
}
