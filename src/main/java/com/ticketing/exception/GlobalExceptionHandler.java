package com.ticketing.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Central exception -> RFC 7807 (application/problem+json) translation.
 * Keeping this as the single mapping point means controllers/services only
 * ever throw typed exceptions and never construct HTTP responses directly —
 * that separation is what keeps the exception model consistent across 20+
 * endpoints instead of ad-hoc ResponseEntity building in every controller.
 *
 * Extends ResponseEntityExceptionHandler rather than hand-rolling an
 * @ExceptionHandler per Spring MVC exception type: the base class already
 * covers ~20 standard exceptions (bad method, unsupported media type,
 * missing param, type mismatch, no route matched, etc.) with a ProblemDetail
 * response. Without it, any of those we hadn't specifically anticipated fell
 * through to handleGeneric() as a 500 — several were found exactly that way
 * during manual testing (a non-UUID path variable, an unparseable date query
 * param, a request to a nonexistent route). Only the handful below where we
 * want our own title/detail wording are overridden; everything else the base
 * class covers gets a correct 4xx instead of a misleading 500 "for free".
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex, WebRequest request) {
        ProblemDetail pd = buildProblemDetail(ex.getStatus(), ex.getErrorCode(), ex.getMessage());
        pd.setType(URI.create("https://ticketing.example.com/errors/" + ex.getErrorCode().toLowerCase()));
        if (ex.getStatus().is5xxServerError()) {
            log.error("Unhandled server-side API exception", ex);
        }
        return pd;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.put(fe.getField(), fe.getDefaultMessage()));

        ProblemDetail pd = buildProblemDetail(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed");
        pd.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.status(status).headers(headers).body(pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return buildProblemDetail(HttpStatus.BAD_REQUEST, "CONSTRAINT_VIOLATION", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return buildProblemDetail(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    // Covers a path variable or query param that can't be converted to its declared
    // type (a non-UUID {id}, an unparseable ?from= date). MethodArgumentTypeMismatchException
    // extends TypeMismatchException, so this single override catches both.
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException matme
                ? matme.getName() : String.valueOf(ex.getPropertyName());
        String detail = "Parameter '" + name + "' has an invalid value: " + ex.getValue();
        ProblemDetail pd = buildProblemDetail(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", detail);
        return ResponseEntity.status(status).headers(headers).body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = buildProblemDetail(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body");
        return ResponseEntity.status(status).headers(headers).body(pd);
    }

    // Any request path that matches no controller mapping and no static resource.
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = buildProblemDetail(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "No such endpoint");
        return ResponseEntity.status(status).headers(headers).body(pd);
    }

    // Final common funnel for every ResponseEntityExceptionHandler default we did NOT
    // override above (unsupported HTTP method, unsupported media type, missing request
    // param, etc). Some of those build their ProblemDetail via createProblemDetail(),
    // others (exceptions that implement ErrorResponse, e.g. HttpRequestMethodNotSupportedException)
    // carry a pre-built body that bypasses it entirely - handleExceptionInternal is the
    // one point every path passes through, so it is where the timestamp is stamped uniformly.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                               HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            pd.setProperty("timestamp", Instant.now());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    @ExceptionHandler({AccessDeniedException.class})
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return buildProblemDetail(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access is denied");
    }

    // Not exercised by the current login flow (AuthService.login throws
    // InvalidCredentialsException instead); kept for any AuthenticationManager-based path.
    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return buildProblemDetail(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildProblemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ProblemDetail buildProblemDetail(HttpStatusCode status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
