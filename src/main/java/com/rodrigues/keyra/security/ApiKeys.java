package com.rodrigues.keyra.security;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The {@code kyr_<8 base62 prefix>_<43 base62 secret>} API key format: generating, parsing and hashing. */
public final class ApiKeys {

    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern FORMAT = Pattern.compile("^kyr_([A-Za-z0-9]{8})_[A-Za-z0-9]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    /** A fresh key: {@code key} is the full secret, shown once; only {@code prefix} and {@code hash} are stored. */
    public record Generated(String prefix, String key, String hash) {}

    public static Generated generate() {
        String prefix = randomBase62(8);
        String key = "kyr_" + prefix + "_" + randomBase62(43);
        return new Generated(prefix, key, hash(key));
    }

    /** The prefix of a well-formed key, empty for anything else. */
    public static Optional<String> prefixOf(String key) {
        Matcher m = FORMAT.matcher(key);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static String hash(String key) {
        return TokenHashing.sha256Hex(key);
    }

    private static String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return sb.toString();
    }
}
