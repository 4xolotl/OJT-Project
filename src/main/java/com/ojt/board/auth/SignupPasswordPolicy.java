package com.ojt.board.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

/**
 * Applies the password checks that are intentionally limited to new accounts.
 * Login accepts legacy passwords and compares their original value unchanged.
 */
@Component
public final class SignupPasswordPolicy {

    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 72;
    private static final int MIN_WEAK_SEQUENCE_LENGTH = 6;
    private static final int MIN_DOMINANT_SPACE_PADDING = 8;
    private static final int MAX_REPEATED_SEPARATOR_LENGTH = 8;

    static final String FORMAT_MESSAGE =
            "비밀번호는 15~72자의 출력 가능한 ASCII 문자와 공백으로 입력해 주세요.";
    static final String GUESSABLE_MESSAGE =
            "널리 사용되거나 쉽게 추측할 수 있는 비밀번호는 사용할 수 없습니다.";
    static final String CONTEXT_MESSAGE =
            "이메일 주소나 그 단순 변형은 비밀번호로 사용할 수 없습니다.";

    private static final String BLOCKLIST_RESOURCE = "/security/common-passwords.txt";
    private static final List<String> APPLICATION_SPECIFIC_PASSWORDS = List.of(
            "4xolotl", "admin", "board", "changeme", "correct horse battery staple",
            "iloveyou", "letmein", "monkey", "ojt board", "ojt project", "ojtproject",
            "password", "qwerty", "secret", "welcome");
    private static final Set<String> PREDICTABLE_AFFIXES = Set.of(
            "a", "aa", "aaa", "abc", "abcd", "abcdef", "abcdefgh", "admin", "asdf",
            "board", "i", "ii", "ojt", "password", "qwerty", "welcome", "x", "xx", "zxcv");
    private static final List<String> WEAK_SEQUENCES = List.of(
            "0123456789",
            "abcdefghijklmnopqrstuvwxyz",
            "qwertyuiopasdfghjklzxcvbnm",
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
            "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
            "`1234567890-=[]\\;',./",
            "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
            "1qazxsw23edcvfr45tgbnhy67ujmki89olp0",
            "qazwsxedcrfvtgbyhnujmikolp");

    private final Set<String> commonPasswords;
    private final Set<String> comparableCommonPasswords;

    public SignupPasswordPolicy() {
        Blocklist blocklist = loadBlocklist();
        this.commonPasswords = blocklist.exact();
        this.comparableCommonPasswords = blocklist.comparable();
    }

    public void validate(String email, String password) {
        if (!hasAllowedFormat(password)) {
            throw new IllegalArgumentException(FORMAT_MESSAGE);
        }
        if (isContextSpecific(password, email)) {
            throw new IllegalArgumentException(CONTEXT_MESSAGE);
        }
        if (isCommon(password) || isObviousPattern(password)) {
            throw new IllegalArgumentException(GUESSABLE_MESSAGE);
        }
    }

    private static boolean hasAllowedFormat(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH
                || password.isBlank()) {
            return false;
        }
        for (int index = 0; index < password.length(); index++) {
            char character = password.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private boolean isCommon(String password) {
        String lowercase = password.toLowerCase(Locale.ROOT);
        String comparable = comparable(password);
        String undecorated = comparable(stripPredictableAffixes(password));
        return commonPasswords.contains(lowercase)
                || isBlockedOrPredictableVariant(comparable, comparableCommonPasswords)
                || (!undecorated.isEmpty()
                    && isBlockedOrPredictableVariant(undecorated, comparableCommonPasswords));
    }

    private static boolean isContextSpecific(String password, String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        String lowercasePassword = password.toLowerCase(Locale.ROOT);
        String comparablePassword = comparable(password);
        String undecoratedPassword = comparable(stripPredictableAffixes(password));
        Set<String> candidates = emailCandidates(email);
        if (candidates.contains(lowercasePassword)) {
            return true;
        }

        Set<String> comparableCandidates = new HashSet<>();
        for (String candidate : candidates) {
            String comparableCandidate = comparable(candidate);
            if (!comparableCandidate.isEmpty()) {
                comparableCandidates.add(comparableCandidate);
            }
        }
        if (isBlockedOrPredictableVariant(comparablePassword, comparableCandidates)
                || isBlockedOrPredictableVariant(undecoratedPassword, comparableCandidates)) {
            return true;
        }

        String repeatedUnit = repeatedUnit(comparablePassword);
        if (repeatedUnit != null && comparableCandidates.contains(repeatedUnit)) {
            return true;
        }
        return false;
    }

    private static boolean isBlockedOrPredictableVariant(String value, Set<String> blockedValues) {
        return hasPredictablyDecoratedMatch(value, candidate ->
                blockedValues.contains(candidate)
                        || hasSingleInsertionVariant(candidate, blockedValues)
                        || hasBlockedRepeatedSides(candidate, blockedValues));
    }

    private static boolean hasPredictablyDecoratedMatch(String value, Predicate<String> matcher) {
        if (matcher.test(value)) {
            return true;
        }
        for (int start = 0; start < value.length(); start++) {
            if (start > 0 && !isPredictableDecoration(value.substring(0, start))) {
                continue;
            }
            for (int end = value.length(); end > start; end--) {
                if (start == 0 && end == value.length()) {
                    continue;
                }
                if (end < value.length() && !isPredictableDecoration(value.substring(end))) {
                    continue;
                }
                if (matcher.test(value.substring(start, end))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPredictableDecoration(String value) {
        return value.length() == 1 || isPredictableAffix(value);
    }

    private static boolean hasSingleInsertionVariant(String value, Set<String> blockedValues) {
        for (int index = 0; index < value.length(); index++) {
            String withoutCharacter = value.substring(0, index) + value.substring(index + 1);
            if (blockedValues.contains(withoutCharacter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBlockedRepeatedSides(String value, Set<String> blockedValues) {
        int maximumSeparatorLength = Math.min(MAX_REPEATED_SEPARATOR_LENGTH, value.length() - 2);
        for (int separatorLength = 1; separatorLength <= maximumSeparatorLength; separatorLength++) {
            int candidateCharacters = value.length() - separatorLength;
            if (candidateCharacters % 2 != 0) {
                continue;
            }
            int candidateLength = candidateCharacters / 2;
            if (blockedValues.contains(value.substring(0, candidateLength))
                    && value.regionMatches(0, value, candidateLength + separatorLength, candidateLength)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPredictableAffix(String value) {
        if (PREDICTABLE_AFFIXES.contains(value)) {
            return true;
        }
        boolean repeated = value.length() >= 2;
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) != value.charAt(0)) {
                repeated = false;
                break;
            }
        }
        if (repeated) {
            return true;
        }
        for (String sequence : List.of("0123456789", "abcdefghijklmnopqrstuvwxyz", "qwertyuiop", "asdfghjkl", "zxcvbnm")) {
            if (sequence.contains(value) || new StringBuilder(sequence).reverse().indexOf(value) >= 0) {
                return value.length() >= 3;
            }
        }
        return false;
    }

    private static Set<String> emailCandidates(String email) {
        Set<String> candidates = new HashSet<>();
        String lowercaseEmail = email.toLowerCase(Locale.ROOT);
        candidates.add(lowercaseEmail);

        int at = lowercaseEmail.indexOf('@');
        String localPart = at < 0 ? lowercaseEmail : lowercaseEmail.substring(0, at);
        int tag = localPart.indexOf('+');
        if (tag >= 0) {
            localPart = localPart.substring(0, tag);
        }
        if (comparable(localPart).length() >= 4) {
            candidates.add(localPart);
        }
        for (String token : localPart.split("[._-]+")) {
            if (comparable(token).length() >= 4) {
                candidates.add(token);
            }
        }
        return Set.copyOf(candidates);
    }

    private boolean isObviousPattern(String password) {
        String pattern = keyboardCanonical(password);
        if (pattern.isEmpty()
                || hasDominantSpacePadding(password)
                || hasDominantRepeatedCharacter(password)
                || hasDominantRepeatedCharacter(pattern)) {
            return true;
        }

        String rawWithoutSpaces = password.toLowerCase(Locale.ROOT).replace(" ", "");
        return hasPredictablyDecoratedMatch(pattern, this::isWeakPattern)
                || hasPredictablyDecoratedMatch(rawWithoutSpaces, this::isWeakPattern)
                || hasPredictablyDecoratedMatch(comparable(password), this::hasRejectedRepeatedUnit);
    }

    private boolean isWeakPattern(String value) {
        return hasRejectedRepeatedUnit(value) || isWeakWalk(value);
    }

    private static boolean hasDominantSpacePadding(String value) {
        int spaces = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == ' ') {
                spaces++;
            }
        }
        return spaces >= MIN_DOMINANT_SPACE_PADDING && spaces * 2 >= value.length();
    }

    private boolean hasRejectedRepeatedUnit(String value) {
        String unit = repeatedUnit(value);
        if (unit == null) {
            return false;
        }
        int repetitions = value.length() / unit.length();
        return repetitions >= 3
                || isBlockedOrPredictableVariant(comparable(unit), comparableCommonPasswords);
    }

    private static boolean hasDominantRepeatedCharacter(String value) {
        int longest = 1;
        int current = 1;
        for (int index = 1; index < value.length(); index++) {
            current = value.charAt(index) == value.charAt(index - 1) ? current + 1 : 1;
            longest = Math.max(longest, current);
        }
        return longest >= 8 && longest * 2 >= value.length();
    }

    private static String repeatedUnit(String value) {
        int maximumUnitLength = value.length() / 2;
        for (int size = 1; size <= maximumUnitLength; size++) {
            boolean matches = true;
            for (int index = size; index < value.length(); index++) {
                if (value.charAt(index) != value.charAt(index % size)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return value.substring(0, size);
            }
        }
        return null;
    }

    private static boolean isWeakWalk(String value) {
        if (value.length() < MIN_WEAK_SEQUENCE_LENGTH) {
            return false;
        }
        for (String sequence : WEAK_SEQUENCES) {
            if (cyclicSequenceContains(sequence, value)
                    || cyclicSequenceContains(new StringBuilder(sequence).reverse().toString(), value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cyclicSequenceContains(String sequence, String candidate) {
        StringBuilder repeated = new StringBuilder(sequence.length() + candidate.length());
        while (repeated.length() < sequence.length() + candidate.length()) {
            repeated.append(sequence);
        }
        return repeated.indexOf(candidate) >= 0;
    }

    private static String stripPredictableAffixes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && !isAsciiLetter(value.charAt(start))) {
            start++;
        }
        while (end > start && !isAsciiLetter(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isAsciiLetter(char character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z';
    }

    private static String comparable(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            char folded = switch (character) {
                case '0' -> 'o';
                case '1', '!', '|' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7', '+' -> 't';
                case '8' -> 'b';
                case '9' -> 'g';
                default -> character;
            };
            if (folded >= 'a' && folded <= 'z' || folded >= '0' && folded <= '9') {
                result.append(folded);
            }
        }
        return result.toString();
    }

    private static String keyboardCanonical(String value) {
        String shifted = "~!@#$%^&*()_+{}|:\"<>?";
        String base = "`1234567890-=[]\\;',./";
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            int shiftedIndex = shifted.indexOf(character);
            if (shiftedIndex >= 0) {
                character = base.charAt(shiftedIndex);
            }
            if (character >= 0x21 && character <= 0x7e) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static Blocklist loadBlocklist() {
        Set<String> exact = new HashSet<>();
        Set<String> comparable = new HashSet<>();
        try (InputStream stream = SignupPasswordPolicy.class.getResourceAsStream(BLOCKLIST_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Password blocklist resource is missing");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(password -> addBlockedPassword(exact, comparable, password));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Password blocklist resource cannot be read", exception);
        }
        APPLICATION_SPECIFIC_PASSWORDS.forEach(password -> addBlockedPassword(exact, comparable, password));
        if (exact.size() < 2_500) {
            throw new IllegalStateException("Password blocklist is unexpectedly small");
        }
        return new Blocklist(Set.copyOf(exact), Set.copyOf(comparable));
    }

    private static void addBlockedPassword(Set<String> exact, Set<String> comparable, String password) {
        exact.add(password.toLowerCase(Locale.ROOT));
        String folded = comparable(password);
        if (!folded.isEmpty()) {
            comparable.add(folded);
        }
    }

    private record Blocklist(Set<String> exact, Set<String> comparable) {
    }
}
