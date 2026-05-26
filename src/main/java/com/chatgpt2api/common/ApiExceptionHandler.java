package com.chatgpt2api.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(error(request, exception.getMessage(), exception.getStatus()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "invalid request"
                : exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error(request, message, HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(request, exception.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleUpstream(IllegalStateException exception, HttpServletRequest request) {
        HttpStatus status = request.getRequestURI().startsWith("/v1/") ? HttpStatus.BAD_GATEWAY : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(error(request, exception.getMessage(), status));
    }

    private Map<String, Object> error(HttpServletRequest request, String message, HttpStatus status) {
        if (request.getRequestURI().equals("/v1/messages")) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("type", status.is5xxServerError() ? "api_error" : errorType(status));
            detail.put("message", message);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("type", "error");
            body.put("error", detail);
            return body;
        }
        if (request.getRequestURI().startsWith("/v1/")) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("message", message);
            detail.put("type", errorType(status));
            detail.put("param", null);
            detail.put("code", errorCode(status));
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("error", detail);
            return body;
        }
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("error", message);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("detail", detail);
        return body;
    }

    private String errorType(HttpStatus status) {
        if (status == HttpStatus.UNAUTHORIZED) {
            return "authentication_error";
        }
        if (status == HttpStatus.FORBIDDEN) {
            return "permission_error";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return "rate_limit_error";
        }
        return status.is4xxClientError() ? "invalid_request_error" : "server_error";
    }

    private String errorCode(HttpStatus status) {
        if (status == HttpStatus.UNAUTHORIZED) {
            return "invalid_api_key";
        }
        if (status == HttpStatus.FORBIDDEN) {
            return "permission_denied";
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return "rate_limit_exceeded";
        }
        return status.is4xxClientError() ? "bad_request" : "upstream_error";
    }
}
