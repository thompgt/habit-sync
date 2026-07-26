package dev.thompgt.habitsync.web;

import dev.thompgt.habitsync.auth.AuthenticationFailedException;
import dev.thompgt.habitsync.sync.ClockDriftException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Every authentication failure returns the same generic 401.
     *
     * <p>The real reason is logged, never returned. Distinguishing "no such account" from
     * "wrong password" hands an attacker a user-enumeration oracle, and distinguishing
     * "expired" from "reused" tells them whether a stolen token is worth replaying.
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAuthFailure(AuthenticationFailedException e) {
        log.info("Authentication failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "authentication_failed", "message", "Authentication failed"));
    }

    /**
     * Malformed sync payloads — unknown entity type, unparseable HLC, oversized batch.
     *
     * <p>400 rather than 500: these describe a defect in the request, and a client that
     * gets a 500 will retry the same bad payload forever.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.info("Rejected malformed request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", "bad_request", "message", String.valueOf(e.getMessage())));
    }

    /**
     * A push carrying a timestamp implausibly far ahead of server time (ADR-001).
     *
     * <p>Given its own error code rather than folded into {@code bad_request} because the
     * remedy is unlike any other 400: nothing about the request is malformed, and the fix is
     * to correct the device's wall clock. A client that cannot distinguish this case can
     * only tell the user "sync failed", which is precisely the wrong instruction.
     */
    @ExceptionHandler(ClockDriftException.class)
    public ResponseEntity<Map<String, Object>> handleClockDrift(ClockDriftException e) {
        log.warn("Rejected push for clock drift: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "error", "clock_drift",
                        "message", "This device's clock is too far ahead of the server's",
                        "serverTimeMillis", e.localMillis(),
                        "maxDriftMillis", e.maxDriftMillis()));
    }

    /** Validation errors are safe to return in detail — they describe the caller's own request. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return ResponseEntity.badRequest()
                .body(Map.of("error", "validation_failed", "fields", fieldErrors));
    }
}
