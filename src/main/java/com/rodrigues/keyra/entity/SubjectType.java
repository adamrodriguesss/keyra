package com.rodrigues.keyra.entity;

/** The account type a token was issued to. Users and managers are separate realms. */
public enum SubjectType {
    USER("keyra-user"),
    MANAGER("keyra-manager");

    private final String audience;

    SubjectType(String audience) {
        this.audience = audience;
    }

    /** The JWT {@code aud} for this realm. Products must only accept {@code keyra-user}. */
    public String audience() {
        return audience;
    }
}
