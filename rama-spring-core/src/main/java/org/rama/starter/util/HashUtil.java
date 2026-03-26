package org.rama.starter.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class HashUtil {
    private HashUtil() {
    }

    public static String SHA256(String input) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            return null;
        }
    }

    public static String SHA256withSelfSalt(String input) {
        if (input == null) {
            return null;
        }
        return SHA256(SelfSalt(input) + input);
    }

    public static String SelfSalt(String input) {
        if (input == null) {
            return null;
        }
        String partOfString = input.substring(0, Math.min(input.length(), 4));
        String hashPartOfString = SHA256(partOfString);
        return hashPartOfString != null ? hashPartOfString.substring(0, 16) : null;
    }
}
