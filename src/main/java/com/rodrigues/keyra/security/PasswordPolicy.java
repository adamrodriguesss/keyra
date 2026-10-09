package com.rodrigues.keyra.security;

/** Password length limits for every account type. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    public static boolean isValidLength(String password) {
        return password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH;
    }
}
