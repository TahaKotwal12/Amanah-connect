package com.amanahconnect.common.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 9457 problem+json with a stable {@code code}. Internal details
 * (messages, stack traces) are logged but never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem =
                Problems.of(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
        List<FieldViolation> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage()))
                        .toList();
        problem.setProperty("errors", errors);
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (!(body instanceof ProblemDetail original)) {
            return super.handleExceptionInternal(ex, body, headers, statusCode, request);
        }
        if (original.getProperties() != null && original.getProperties().containsKey("code")) {
            return super.handleExceptionInternal(ex, original, headers, statusCode, request);
        }
        ErrorCode code = Problems.codeFor(statusCode);
        ProblemDetail problem = Problems.of(code, original.getDetail());
        problem.setStatus(statusCode.value());
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage());
        if (!ex.details().isEmpty()) {
            problem.setProperty("errors", ex.details());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        if (ex instanceof RateLimitedException limited) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
        }
        return response.body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(Problems.of(ErrorCode.FORBIDDEN, "You do not have access to this resource."));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(Problems.of(ErrorCode.UNAUTHENTICATED, "Authentication is required."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(
                Problems.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred."));
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** One invalid field in a {@code VALIDATION_FAILED} response. */
    public record FieldViolation(String field, String message) {}
}
