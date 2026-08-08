package com.yanyue.rag.api;

import com.yanyue.rag.application.chat.suggestion.QuestionSuggestionUnavailableException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), message(error))).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", "Request validation failed",
                Instant.now(), fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_ARGUMENT", exception.getMessage(),
                Instant.now(), List.of()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException exception) {
        var code = exception.getStatusCode().toString().replace(' ', '_');
        var message = exception.getReason() == null ? "Request could not be completed" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiError(code, message, Instant.now(), List.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException exception) {
        LOGGER.error("Database operation failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("DATABASE_ERROR", "The database operation could not be completed",
                        Instant.now(), List.of()));
    }

    @ExceptionHandler(QuestionSuggestionUnavailableException.class)
    ResponseEntity<ApiError> questionSuggestions(QuestionSuggestionUnavailableException exception) {
        LOGGER.warn("Question suggestion request failed: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("QUESTION_SUGGESTIONS_UNAVAILABLE", "暂时无法生成建议，请稍后重试",
                        Instant.now(), List.of()));
    }

    private String message(FieldError error) {
        return error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage();
    }

    public record ApiError(String code, String message, Instant timestamp, List<FieldViolation> fields) {
    }

    public record FieldViolation(String field, String message) {
    }
}
