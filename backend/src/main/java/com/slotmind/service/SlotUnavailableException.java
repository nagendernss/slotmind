package com.slotmind.service;

/** The target slot is no longer bookable (mapped to 409 SLOT_UNAVAILABLE). */
public class SlotUnavailableException extends RuntimeException {
    public SlotUnavailableException(String message) { super(message); }
}
