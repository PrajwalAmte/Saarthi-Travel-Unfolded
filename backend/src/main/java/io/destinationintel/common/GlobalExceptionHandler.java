package io.destinationintel.common;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every error to an RFC 9457 problem document with a stable machine-readable
 * {@code code}. Internal exception details are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail pd = problem(ex.status(), ex.code(), ex.getMessage());
        ex.details().forEach(pd::setProperty);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
        if (ex instanceof RateLimitedException rl) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, rl.retryAfter().toSeconds())));
        }
        return builder.body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message",
                        fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Some fields are invalid.");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(err -> Map.of(
                        "field", r.getMethodParameter().getParameterName() == null ? "parameter"
                                : r.getMethodParameter().getParameterName(),
                        "message", err.getDefaultMessage() == null ? "is invalid" : err.getDefaultMessage())))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Some parameters are invalid.");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Some parameters are invalid.");
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> handleBadInput(Exception ex) {
        if (log.isDebugEnabled()) {
            log.debug("Unreadable request: {}", ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()).lines().findFirst().orElse(""));
        }
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "bad_request", "The request could not be read."));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ProblemDetail> handleDenied(Exception ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problem(HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to do that."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(problem(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "not_found", "Not found."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                        "Something went wrong on our side. Please try again."));
    }

    /** The problem+json body every error response uses (also written by the security filters). */
    public static ProblemDetail problem(HttpStatus status, String code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("about:blank"));
        pd.setTitle(status.getReasonPhrase());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("code", code);
        props.forEach(pd::setProperty);
        return pd;
    }
}
