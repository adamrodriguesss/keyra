package com.rodrigues.keyra.exception;

/** The request conflicts with the current state (duplicate slug, removing the last admin, ...). Maps to 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
