package com.slotmind.web;

import com.slotmind.domain.IllegalStateTransitionException;
import com.slotmind.service.NotFoundException;
import com.slotmind.service.SlotUnavailableException;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleObjectStateException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** Error JSON: { "error": SLOT_UNAVAILABLE | INVALID_STATE | NOT_FOUND | VALIDATION, "message": ... } */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String error, String message) {}

    private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) { return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage()); }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> invalidState(IllegalStateTransitionException e) {
        return body(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    @ExceptionHandler(SlotUnavailableException.class)
    public ResponseEntity<ApiError> slotUnavailable(SlotUnavailableException e) {
        return body(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", e.getMessage());
    }

    /** Lost optimistic-lock race or DB unique constraint (appointment.slot_id / pendingKey) => someone else got there first. */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class, StaleObjectStateException.class,
            DataIntegrityViolationException.class})
    public ResponseEntity<ApiError> conflict(Exception e) {
        return body(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", "The slot was just taken by another booking — nothing was changed. Get a new suggestion.");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> validation(Exception e) {
        String msg = e instanceof HttpMessageNotReadableException ? "Malformed or invalid request body" : e.getMessage();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", msg);
    }

    public static Map<String, String> toMap(Throwable e) {
        String code = e instanceof NotFoundException ? "NOT_FOUND"
                : e instanceof SlotUnavailableException ? "SLOT_UNAVAILABLE"
                : e instanceof IllegalStateTransitionException ? "INVALID_STATE"
                : e instanceof IllegalArgumentException ? "VALIDATION" : "INTERNAL";
        return Map.of("error", code, "message", String.valueOf(e.getMessage()));
    }
}
