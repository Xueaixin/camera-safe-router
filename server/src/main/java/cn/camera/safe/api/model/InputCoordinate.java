package cn.camera.safe.api.model;

import cn.camera.safe.coordinate.CoordinateSystem;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record InputCoordinate(
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @NotNull CoordinateSystem coordinateSystem,
        @NotNull CoordinateSource source,
        @PositiveOrZero Double accuracyMeters,
        Instant timestamp) {
}
