package com.schemaguard.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class EtagUtil {

    private EtagUtil() {}

    public static String sha256Etag(String json) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            String hex = toHex(hashBytes);
            // Return unquoted ETag - Spring's .eTag() method will add quotes
            return hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 always exists in Java, but handle defensively
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
