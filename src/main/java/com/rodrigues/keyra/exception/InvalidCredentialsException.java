package com.rodrigues.keyra.exception;

/** Wrong email or password, or a disabled account. Deliberately does not say which. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password.");
    }
}
