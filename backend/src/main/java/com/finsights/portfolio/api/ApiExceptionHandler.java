package com.finsights.portfolio.api;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

// Scoped to @RestController beans only — an unscoped advice also intercepts exceptions from
// routes that never reach a controller at all (e.g. NoResourceFoundException for an unmatched
// static path like a disabled /h2-console), turning what should be a plain 404 into a
// misleading 500 from this handler's own last-resort catch. Real /api/** errors are unaffected:
// they all originate from an actual @RestController method either way.
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    // Render ResponseStatusException as a JSON body directly, so unauthenticated endpoints
    // (sign-in, registration) don't forward to a secured /error and come back as an opaque 403.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> statusException(ResponseStatusException exception) {
        String message = exception.getReason() != null ? exception.getReason() : exception.getStatusCode().toString();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).orElse("Invalid request");
        return Map.of("message", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }

    // Last-resort net: an unanticipated bug (e.g. a null field on one bad record) must never
    // surface as a raw unhandled exception — log it for diagnosis and return a safe, generic
    // response so the rest of the app keeps working.
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    Map<String, String> unexpected(Exception exception) {
        log.warn("Unhandled exception serving API request", exception);
        return Map.of("message", "Something went wrong on our end. Please try again.");
    }
}
