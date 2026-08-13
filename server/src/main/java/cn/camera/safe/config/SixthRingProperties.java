package cn.camera.safe.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.sixth-ring")
public record SixthRingProperties(
        @NotBlank String boundaryPath,
        boolean requireApprovedBoundary,
        @DecimalMin("0.0") double cameraOutsideMarginMeters,
        @Min(1) int portalDistanceTierMeters,
        @DecimalMin("0.0") double maxSnapDistanceMeters,
        @DecimalMin("0.0") double maxJoinGapMeters,
        @Min(1) int maxVisitedStates,
        @NotNull Duration searchTimeout,
        boolean parallelCandidateEvaluation,
        @Min(1) int candidateEvaluationThreads,
        @DecimalMin("0.0") double provincialBorderToleranceMeters) {

    public SixthRingProperties {
        if (searchTimeout == null || searchTimeout.isZero() || searchTimeout.isNegative()) {
            throw new IllegalArgumentException("sixth-ring search timeout must be positive");
        }
        if (candidateEvaluationThreads <= 0) {
            throw new IllegalArgumentException(
                    "sixth-ring candidate evaluation threads must be positive");
        }
    }
}
