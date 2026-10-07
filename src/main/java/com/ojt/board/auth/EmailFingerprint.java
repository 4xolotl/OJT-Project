package com.ojt.board.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EmailFingerprint {

    private EmailFingerprint() {
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(digest(null, value));
    }

    static int bucket(String value, byte[] secret, int bucketCount) {
        if (bucketCount < 1) {
            throw new IllegalArgumentException("Account bucket count must be positive");
        }
        byte[] hash = digest(secret, value);
        int prefix = (hash[0] & 0xff) << 24
                | (hash[1] & 0xff) << 16
                | (hash[2] & 0xff) << 8
                | (hash[3] & 0xff);
        return Math.floorMod(prefix, bucketCount);
    }

    private static byte[] digest(byte[] prefix, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (prefix != null) {
                digest.update(prefix);
            }
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
