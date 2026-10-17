package com.travelgo.util;

/** Brevo's API and webhook can enclose the same opaque message ID differently. */
public final class ProviderMessageIds {
    private ProviderMessageIds() { }

    public static String normalize(String value) {
        if (value == null || value.isEmpty() || value.length() > 255)
            throw new IllegalArgumentException("Invalid provider message identifier.");
        String id = value;
        if (id.startsWith("<") && id.endsWith(">")) id = id.substring(1, id.length() - 1);
        if (id.isEmpty() || id.chars().anyMatch(c -> c < 33 || c > 126 || c == '<' || c == '>'))
            throw new IllegalArgumentException("Invalid provider message identifier.");
        return id;
    }

    public static boolean same(String first, String second) {
        try { return normalize(first).equals(normalize(second)); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
