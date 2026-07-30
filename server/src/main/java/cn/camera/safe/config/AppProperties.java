package cn.camera.safe.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull @Valid Routing routing,
        @NotNull @Valid Cameras cameras,
        @NotNull @Valid Admin admin) {

    public record Routing(
            @NotBlank String pbfPath,
            @NotBlank String graphCachePath,
            @DecimalMin("1.0") @DecimalMax("500.0") double safetyRadiusMeters,
            @Min(1) int calculationThreads,
            @Min(1) int calculationQueueCapacity,
            @NotNull Duration requestTimeout,
            @Min(1) int maxVisitedNodes) {
    }

    public record Cameras(
            @NotBlank String jsonPath,
            @NotBlank String snapshotPath,
            boolean sourceCoordinateVerified,
            @Min(1) int maxBboxResults,
            @DecimalMin("0.001") @DecimalMax("10.0") double maxBboxSpanDegrees) {
    }

    public record Admin(boolean localOnly) {
    }
}
