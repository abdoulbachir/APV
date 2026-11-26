package org.apvgakasinay.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure into the {@code Error} schema of the contract. Never leaks internals. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> clientError(ApiException error, HttpServletRequest request) {
        Map<String, Object> body = body(error.code(), error.getMessage(), request);
        if (error.fieldName() != null) {
            body.put("fields", List.of(Map.of("name", error.fieldName(), "code", error.fieldCode())));
            // "fields" is listed before "requestId" in the contract examples.
            body.put("requestId", body.remove("requestId"));
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON);
        error.headers().forEach(response::header);
        return response.body(Map.of("error", body));
    }

    /**
     * Anything that is not one of the approved GET endpoints does not exist, whatever the
     * method. The contract has no "method not allowed" code, so this is 404 not_found.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<Map<String, Object>> notFound(HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, body("not_found", "Not found", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception error, HttpServletRequest request) {
        log.error("Unexpected error, request {}", request.getAttribute(RequestIdFilter.ATTRIBUTE), error);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, body("internal_error", "Unexpected error", request));
    }

    private static Map<String, Object> body(String code, String message, HttpServletRequest request) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
        return error;
    }

    private static ResponseEntity<Map<String, Object>> respond(HttpStatus status, Map<String, Object> error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", error));
    }
}
