package cn.camera.safe.api.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record RouteRequest(
        @NotNull @Valid InputCoordinate start,
        @NotNull @Valid InputCoordinate end,
        @NotNull VehicleType vehicle) {
}
