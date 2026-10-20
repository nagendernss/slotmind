package com.slotmind.domain;

/** Thrown by entity intent methods on an illegal state transition (mapped to HTTP 409 INVALID_STATE). */
public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String message) { super(message); }
}
