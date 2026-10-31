package com.travelgo.util;

import java.nio.charset.StandardCharsets;

/** Shared BCrypt-compatible password limits for every account entry point. */
public final class PasswordPolicy {
    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicy() { }

    public static boolean isValid(String password) {
        return password != null
                && password.length() >= MIN_CHARACTERS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    public static void requireValid(String password) {
        if (!isValid(password)) {
            throw new IllegalArgumentException("Use a password of 12–72 UTF-8 bytes.");
        }
    }
}
