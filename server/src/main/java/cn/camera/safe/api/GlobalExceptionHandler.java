package cn.camera.safe.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public final class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return response(exception.status(), exception.code(), exception.getMessage(), exception.details(), request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            MissingServletRequestParameterException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ApiError> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        String message = "请求格式或参数无效";
        if (exception instanceof MethodArgumentNotValidException validation) {
            details.put("fields", validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .distinct()
                    .toList());
            if (validation.getBindingResult().getFieldErrors().stream()
                    .anyMatch(error -> error.getField().endsWith(".lng")
                            || error.getField().endsWith(".lat"))) {
                code = ErrorCode.INVALID_COORDINATE;
                message = "坐标无效";
            }
        } else if (exception instanceof BindException binding) {
            details.put("fields", binding.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .distinct()
                    .toList());
        } else if (exception instanceof HttpMessageNotReadableException unreadable
                && findCause(unreadable, InvalidFormatException.class) instanceof InvalidFormatException invalid
                && invalid.getTargetType() == cn.camera.safe.coordinate.CoordinateSystem.class) {
            code = ErrorCode.UNSUPPORTED_COORDINATE_SYSTEM;
            message = "不支持的坐标系";
        } else if (exception instanceof MethodArgumentTypeMismatchException mismatch
                && mismatch.getRequiredType() == cn.camera.safe.coordinate.CoordinateSystem.class) {
            code = ErrorCode.UNSUPPORTED_COORDINATE_SYSTEM;
            message = "不支持的坐标系";
        }
        return response(HttpStatus.BAD_REQUEST, code, message, details, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        LOGGER.error("Unhandled request failure requestId={} type={}",
                requestId, exception.getClass().getName(), exception);
        return new ResponseEntity<>(new ApiError(
                ErrorCode.INTERNAL_ERROR,
                "服务内部错误",
                requestId,
                Instant.now(),
                Map.of()), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status,
            ErrorCode code,
            String message,
            Map<String, Object> details,
            HttpServletRequest request) {
        return new ResponseEntity<>(new ApiError(
                code, message, requestId(request), Instant.now(), details), status);
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }

    private static Throwable findCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }
}
