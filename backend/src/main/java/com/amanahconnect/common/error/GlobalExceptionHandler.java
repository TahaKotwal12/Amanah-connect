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
                        .map(e -> new FieldViolation(e.getField(), e.isBindingFailure() ? "invalid value" : e.getDefaultMessage()))
                        .toList();
        problem.setProperty("errors", errors);
        return super.handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.amanahconnect.common.web.RequestSizeLimitFilter.BodyTooLargeException) {
                ProblemDetail problem = Problems.of(ErrorCode.PAYLOAD_TOO_LARGE, "The request body is too large.");
                return super.handleExceptionInternal(ex, problem, headers, org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, request);
            }
        }
        return super.handleHttpMessageNotReadable(ex, headers, status, request);
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

    /** Violations from method-level validation (e.g. @Valid on a @RequestParam list) become the same shape. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(jakarta.validation.ConstraintViolationException ex) {
        ProblemDetail problem = Problems.of(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.");
        problem.setProperty(
                "errors",
                ex.getConstraintViolations().stream()
                        .map(v -> new FieldViolation(leaf(v.getPropertyPath().toString()), v.getMessage()))
                        .toList());
        return respond(problem);
    }

    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** A concurrent edit lost the race (@Version): the client should reload and retry. */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return respond(Problems.of(ErrorCode.VERSION_CONFLICT, "The record was changed by someone else. Reload and try again."));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.code(), ex.getMessage());
        if (!ex.details().isEmpty()) {
            problem.setProperty("errors", ex.details());
        }
        ex.properties().forEach(problem::setProperty);
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
