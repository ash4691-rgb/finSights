package com.finsights.portfolio.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
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
}
