package com.ojt.board.user;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Defines the single email identity representation used by storage, authentication and throttling.
 * Changing this identity rule requires a new database migration before application code changes.
 */
public final class EmailCanonicalizer {

    private static final String LOCAL_ASCII_SPECIALS = "!#$%&'*+-/=?^_`{|}~.";

    private EmailCanonicalizer() {
    }

    public static String canonicalize(String email) {
        if (email == null) {
            return "";
        }
        if (!email.chars().allMatch(character -> character < 128)) {
            return email;
        }
        String stripped = email.strip();
        return stripped.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidCanonical(String email, int maxLength) {
        if (email == null || maxLength < 1 || email.length() > maxLength
                || !email.equals(canonicalize(email))) {
            return false;
        }
        int separator = email.indexOf('@');
        if (separator <= 0 || separator != email.lastIndexOf('@') || separator >= email.length() - 1) {
            return false;
        }
        String local = email.substring(0, separator);
        String domain = email.substring(separator + 1);
        return validLocalPart(local) && validAsciiDomain(domain);
    }

    private static boolean validLocalPart(String local) {
        if (local.getBytes(StandardCharsets.UTF_8).length > 64
                || local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        return local.chars().allMatch(character -> character < 128
                && (Character.isLetterOrDigit(character)
                || LOCAL_ASCII_SPECIALS.indexOf(character) >= 0));
    }

    private static boolean validAsciiDomain(String domain) {
        if (domain.isEmpty() || domain.length() > 253 || domain.startsWith(".") || domain.endsWith(".")) {
            return false;
        }
        for (String label : domain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            if (!label.chars().allMatch(character -> character < 128
                    && (Character.isLetterOrDigit(character) || character == '-'))) {
                return false;
            }
        }
        return true;
    }
}
