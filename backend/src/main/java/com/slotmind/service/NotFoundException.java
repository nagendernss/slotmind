package com.slotmind.service;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String what, String id) { super(what + " " + id + " not found"); }
}
