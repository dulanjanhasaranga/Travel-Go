package com.travelgo.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable server-generated metadata fits the existing VARCHAR(255); it is not a URL. */
record SupabaseDocumentReference(String key, long length, String sha256) {
    private static final String PREFIX="supabase:v1:";
    private static final Pattern FORMAT=Pattern.compile("supabase:v1:([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(pdf|png|jpg)):([1-9][0-9]{0,7}):([0-9a-f]{64})");

    static SupabaseDocumentReference fresh(byte[] bytes,String extension) {
        return new SupabaseDocumentReference(UUID.randomUUID()+"."+extension,bytes.length,digest(bytes));
    }
    static SupabaseDocumentReference parse(String value) throws IOException {
        if (value == null || value.length() > 255) throw VisaDocumentStorage.unavailable();
        var match=FORMAT.matcher(value);
        if (!match.matches()) throw VisaDocumentStorage.unavailable();
        long length=Long.parseLong(match.group(3));
        if (length > VisaDocumentStorage.MAX_DOCUMENT_BYTES) throw VisaDocumentStorage.unavailable();
        return new SupabaseDocumentReference(match.group(1),length,match.group(4));
    }
    static boolean isRemote(String value) { return value != null && value.startsWith("supabase:"); }
    String encode() { return PREFIX+key+":"+length+":"+sha256; }
    String extension() { return key.substring(key.lastIndexOf('.')+1); }
    boolean matches(byte[] bytes) {
        return bytes != null && bytes.length == length
                && MessageDigest.isEqual(HexFormat.of().parseHex(sha256),HexFormat.of().parseHex(digest(bytes)));
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }
}
