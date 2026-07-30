package cn.camera.safe.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        ErrorCode code,
        String message,
        String requestId,
        Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details) {
}
