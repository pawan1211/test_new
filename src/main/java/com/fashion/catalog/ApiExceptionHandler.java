
package com.fashion.catalog;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.server.ResponseStatusException;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ApiExceptionHandler.class);

    /*
     * Common response format:
     *
     * {
     *   "status": 400,
     *   "error": "Invalid request",
     *   "path": "/api/v1/example"
     * }
     */

    private ResponseEntity<Map<String, Object>> response(
            HttpStatusCode status,
            String message,
            HttpServletRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("status", status.value());
        body.put("error", message);

        if (request != null) {
            body.put("path", request.getRequestURI());
        }

        return ResponseEntity.status(status).body(body);
    }

    // --------------------------------------------------
    // 1. ResponseStatusException (400, 401, 403, 404, etc.)
    // --------------------------------------------------

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException ex,
            HttpServletRequest request) {

        HttpStatusCode status = ex.getStatusCode();

        String message = ex.getReason() != null
                ? ex.getReason()
                : "Request failed";

        if (status.is5xxServerError()) {
            log.error("Server error at {}: {}",
                    request.getRequestURI(), message, ex);
        }

        return response(status, message, request);
    }

    // --------------------------------------------------
    // 2. Resource not found
    // --------------------------------------------------

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            NoSuchElementException ex,
            HttpServletRequest request) {

        log.warn("Resource not found at {}: {}",
                request.getRequestURI(), ex.getMessage());

        return response(
                HttpStatus.NOT_FOUND,
                "Requested resource was not found",
                request
        );
    }

    // --------------------------------------------------
    // 3. Invalid request body / malformed JSON
    // --------------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        log.warn("Invalid request body at {}",
                request.getRequestURI());

        return response(
                HttpStatus.BAD_REQUEST,
                "Invalid or malformed request body",
                request
        );
    }

    // --------------------------------------------------
    // 4. @Valid request body validation errors
    // --------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getField() + ": "
                        + (error.getDefaultMessage() != null
                        ? error.getDefaultMessage()
                        : "Invalid value"))
                .orElse("Request validation failed");

        log.warn("Validation failed at {}: {}",
                request.getRequestURI(), message);

        return response(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }

    // --------------------------------------------------
    // 5. Form / model binding validation errors
    // --------------------------------------------------

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Map<String, Object>> handleBindException(
            BindException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getField() + ": "
                        + (error.getDefaultMessage() != null
                        ? error.getDefaultMessage()
                        : "Invalid value"))
                .orElse("Invalid request parameters");

        return response(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }

    // --------------------------------------------------
    // 6. Constraint validation errors
    // --------------------------------------------------

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        String message = ex.getConstraintViolations()
                .stream()
                .findFirst()
                .map(violation ->
                        violation.getPropertyPath() + ": "
                                + violation.getMessage())
                .orElse("Request validation failed");

        return response(
                HttpStatus.BAD_REQUEST,
                message,
                request
        );
    }

    // --------------------------------------------------
    // 7. Method parameter validation (Spring 6.1+)
    // --------------------------------------------------

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(
            HandlerMethodValidationException ex,
            HttpServletRequest request) {

        log.warn("Method validation failed at {}",
                request.getRequestURI());

        return response(
                HttpStatus.BAD_REQUEST,
                "Request parameter validation failed",
                request
        );
    }

    // --------------------------------------------------
    // 8. Missing required query parameter
    // --------------------------------------------------

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.BAD_REQUEST,
                "Missing required parameter: " + ex.getParameterName(),
                request
        );
    }

    // --------------------------------------------------
    // 9. Invalid parameter type
    // --------------------------------------------------

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.BAD_REQUEST,
                "Invalid value for parameter: " + ex.getName(),
                request
        );
    }

    // --------------------------------------------------
    // 10. Invalid argument
    // --------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        log.warn("Invalid argument at {}: {}",
                request.getRequestURI(), ex.getMessage());

        return response(
                HttpStatus.BAD_REQUEST,
                ex.getMessage() != null
                        ? ex.getMessage()
                        : "Invalid request parameter",
                request
        );
    }

    // --------------------------------------------------
    // 11. File upload size exceeded
    // --------------------------------------------------

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(
            MaxUploadSizeExceededException ex,
            HttpServletRequest request) {

        log.warn("Upload size exceeded at {}",
                request.getRequestURI());

        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Uploaded file exceeds the maximum allowed size",
                request
        );
    }

    // --------------------------------------------------
    // 12. Unsupported media type
    // --------------------------------------------------

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(
            HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported content type",
                request
        );
    }

    // --------------------------------------------------
    // 13. HTTP method not supported
    // --------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {

        return response(
                HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method not allowed for this endpoint",
                request
        );
    }

    // --------------------------------------------------
    // 14. Missing request header
    // --------------------------------------------------

    /**
     * A missing "Authorization" header means the caller is not authenticated,
     * which is a 401 and not a server fault. Previously this fell through to the
     * catch-all below, so every signed-out visit to a protected endpoint came
     * back as 500 "Unexpected server error" and logged a full stack trace.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(
            MissingRequestHeaderException ex,
            HttpServletRequest request) {

        boolean isAuthorization =
                ex.getHeaderName().equalsIgnoreCase("Authorization");

        if (isAuthorization) {
            log.warn("Unauthenticated request to {}: missing Authorization header",
                    request.getRequestURI());

            return response(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required",
                    request
            );
        }

        return response(
                HttpStatus.BAD_REQUEST,
                "Missing required header: " + ex.getHeaderName(),
                request
        );
    }

//     // --------------------------------------------------
//     // 15. Authentication failures
//     // --------------------------------------------------

//     @ExceptionHandler(AuthenticationException.class)
//     public ResponseEntity<Map<String, Object>> handleAuthentication(
//             AuthenticationException ex,
//             HttpServletRequest request) {

//         log.warn("Authentication failed at {}",
//                 request.getRequestURI());

//         return response(
//                 HttpStatus.UNAUTHORIZED,
//                 "Authentication required or credentials are invalid",
//                 request
//         );
//     }

//     // --------------------------------------------------
//     // 15. Access denied / authorization failures
//     // --------------------------------------------------

//     @ExceptionHandler(AccessDeniedException.class)
//     public ResponseEntity<Map<String, Object>> handleAccessDenied(
//             AccessDeniedException ex,
//             HttpServletRequest request) {

//         log.warn("Access denied at {}",
//                 request.getRequestURI());

//         return response(
//                 HttpStatus.FORBIDDEN,
//                 "You do not have permission to access this resource",
//                 request
//         );
//     }

    // --------------------------------------------------
    // 16. Unknown URL
    // --------------------------------------------------

    /**
     * An unmapped path is a 404. Without this it reached the catch-all and was
     * reported as a 500 with a stack trace, which made ordinary typos look like
     * server outages.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            NoResourceFoundException ex,
            HttpServletRequest request) {

        log.warn("No API endpoint at {}",
                request.getRequestURI());

        return response(
                HttpStatus.NOT_FOUND,
                "No API endpoint at " + request.getRequestURI(),
                request
        );
    }

    // --------------------------------------------------
    // 17. Database integrity violations
    // --------------------------------------------------

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        log.error("Database integrity violation at {}",
                request.getRequestURI(), ex);

        return response(
                HttpStatus.CONFLICT,
                "Database constraint violation. Check duplicate or invalid data",
                request
        );
    }

    // --------------------------------------------------
    // 18. Other database errors
    // --------------------------------------------------

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDatabase(
            DataAccessException ex,
            HttpServletRequest request) {

        String errorId = UUID.randomUUID().toString();

        log.error("Database error. Error ID: {}. Path: {}",
                errorId, request.getRequestURI(), ex);

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("status", 500);
        body.put("error", "Database operation failed");
        body.put("errorId", errorId);
        body.put("path", request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }

    // --------------------------------------------------
    // 19. Any unexpected exception
    // --------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        String errorId = UUID.randomUUID().toString();

        log.error(
                "Unexpected API exception. Error ID: {}. Path: {}",
                errorId,
                request.getRequestURI(),
                ex
        );

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("status", 500);
        body.put("error", "Unexpected server error");
        body.put("errorId", errorId);
        body.put("path", request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }
}