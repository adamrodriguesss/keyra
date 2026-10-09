package com.rodrigues.keyra.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt (cost 12) over base64(SHA-256(password)).
 *
 * <p>BCrypt only reads the first 72 bytes and Spring Security rejects longer input, but Keyra allows
 * passwords up to 128 characters. Pre-hashing turns any password into a fixed 44-byte string so every
 * character counts.
 */
public final class PreHashedBCryptPasswordEncoder implements PasswordEncoder {

    private static final int BCRYPT_COST = 12;

    private final BCryptPasswordEncoder bcrypt;

    public PreHashedBCryptPasswordEncoder() {
        this(BCRYPT_COST);
    }

    PreHashedBCryptPasswordEncoder(int cost) {
        this.bcrypt = new BCryptPasswordEncoder(cost);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(preHash(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return bcrypt.matches(preHash(rawPassword), encodedPassword);
    }

    private static String preHash(CharSequence rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
