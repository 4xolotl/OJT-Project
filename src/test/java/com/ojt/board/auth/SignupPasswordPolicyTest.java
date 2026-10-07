package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SignupPasswordPolicyTest {

    private static final String EMAIL = "john.doe+tag@example.com";

    private final SignupPasswordPolicy policy = new SignupPasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
            "violet river 829",
            "singlecategoryphrase",
            "SINGLECATEGORYPHRASE",
            "204938576102938",
            "!^>_&{?~#)<[$]+",
            "  violet river 829  ",
            "BlueJohnDoeCoffeeTrail"
    })
    void acceptsLongPasswordsWithoutACharacterCompositionRequirement(String password) {
        assertDoesNotThrow(() -> policy.validate(EMAIL, password));
    }

    @Test
    void acceptsTheMaximumLengthWithoutChangingThePassword() {
        String password = "R7!violet-river_2026/candle?meadow#orbit="
                + "Lake9%forest&cloud2*harborTrail";
        assertEquals(SignupPasswordPolicy.MAX_LENGTH, password.length());
        assertDoesNotThrow(() -> policy.validate(EMAIL, password));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFormats")
    void rejectsValuesOutsideTheBcryptCompatibleInputBoundary(String description, String password) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> policy.validate(EMAIL, password));
        assertEquals(SignupPasswordPolicy.FORMAT_MESSAGE, error.getMessage());
        if (password != null && !password.isEmpty()) {
            assertFalse(error.getMessage().contains(password));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password1234567",
            "P@ssw0rd2026!!!!",
            "P@ssw0rdP@ssw0rd",
            "P@ssw0rdAa1!2026",
            "passwordxpasswordx",
            "correct horse battery staple",
            "correct horse battery staplecorrect horse battery staple",
            "Mailcreated5240",
            "abcabcabcabcabc",
            "abababababababa",
            "123412341234123",
            "abcabcabcabcabX",
            "!!!!!!!!!!!!!!!!",
            "123456789012345",
            "12345678901234?",
            "qwertyuiopasdfgh",
            "xqwertyuiopasdfgh",
            "xabababababababa",
            "1qaz2wsx3edc4rfv",
            "asdfghjklasdfgh",
            "!\"#$%&'()*+,-./",
            "!@#$%^&*()_+{}|",
            "abcdefghij     ",
            "!@#$%^&*()     ",
            "4xolotlpassword",
            "abcdpasswordxyz",
            "passwordabcdefghi",
            "passwordxpassword",
            "password1password",
            "mnbvcxzlkjhgfds"
    })
    void rejectsBlocklistedAndObviousWholePasswordPatterns(String password) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> policy.validate(EMAIL, password));
        assertEquals(SignupPasswordPolicy.GUESSABLE_MESSAGE, error.getMessage());
        assertFalse(error.getMessage().contains(password));
    }

    @ParameterizedTest
    @MethodSource("dominantSpacePaddings")
    void rejectsDominantSpacePadding(String password) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> policy.validate(EMAIL, password));
        assertEquals(SignupPasswordPolicy.GUESSABLE_MESSAGE, error.getMessage());
        assertFalse(error.getMessage().contains(password));
    }

    @ParameterizedTest
    @MethodSource("allowedSpacePassphrases")
    void keepsNonDominantOuterAndInternalSpaces(String password) {
        assertDoesNotThrow(() -> policy.validate(EMAIL, password));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "person@example.com",
            "John.Doe20262026!",
            "johndoejohndoejohndoe",
            "johnsmithjohnsmith",
            "abcdjohndoezxcv",
            "johndoexjohndoe",
            "johndoe1johndoe",
            "johnxsmith2026!",
            "myverylongemailidentifiermyverylongemailidentifier"
    })
    void rejectsEmailAndLimitedPredictableDerivatives(String password) {
        String email = switch (password) {
            case "person@example.com" -> "person@example.com";
            case "johnsmithjohnsmith", "johnxsmith2026!" -> "john.smith@example.com";
            case "myverylongemailidentifiermyverylongemailidentifier" ->
                    "myverylongemailidentifier@example.com";
            default -> EMAIL;
        };
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> policy.validate(email, password));
        assertEquals(SignupPasswordPolicy.CONTEXT_MESSAGE, error.getMessage());
        assertFalse(error.getMessage().contains(password));
        assertFalse(error.getMessage().contains(email));
    }

    @Test
    void limitsRepeatedBlockedValueSeparatorsToEightCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(EMAIL, "passwordkXmZvQrNpassword"));
        assertDoesNotThrow(() -> policy.validate(EMAIL, "passwordkXmZvQrNspassword"));
    }

    @Test
    void rejectsARepeatedEmailLocalPartAtTheMaximumPasswordLength() {
        String localPart = "r7violet9candle2meadow4orbit6lake8xy";
        String password = localPart.repeat(2);
        assertEquals(36, localPart.length());
        assertEquals(SignupPasswordPolicy.MAX_LENGTH, password.length());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> policy.validate(localPart + "@example.com", password));
        assertEquals(SignupPasswordPolicy.CONTEXT_MESSAGE, error.getMessage());
    }

    private static Stream<Arguments> invalidFormats() {
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("empty", ""),
                Arguments.of("blank", " ".repeat(15)),
                Arguments.of("below minimum", "v".repeat(SignupPasswordPolicy.MIN_LENGTH - 1)),
                Arguments.of("above maximum", "v".repeat(SignupPasswordPolicy.MAX_LENGTH + 1)),
                Arguments.of("ASCII control", "violet river\n829"),
                Arguments.of("Unicode", "보라색 강과 들판의 등불"));
    }

    private static Stream<String> dominantSpacePaddings() {
        return Stream.of(
                "a" + " ".repeat(14),
                " ".repeat(7) + "a" + " ".repeat(7),
                "abc" + " ".repeat(12),
                " ".repeat(5) + "abcde" + " ".repeat(5),
                "a1b2c3" + " ".repeat(9),
                "x" + " ".repeat(13) + "y",
                "ab" + " ".repeat(11) + "cd",
                "a1b" + " ".repeat(9) + "c2d",
                " ".repeat(4) + "aZ7!mQ2?" + " ".repeat(4),
                "aZ7!" + " ".repeat(8) + "mQ2?",
                "a    b    c    d",
                "a   x   c   p   e",
                "a  x  c  p  e  z");
    }

    private static Stream<String> allowedSpacePassphrases() {
        return Stream.of(
                " ".repeat(8) + "violet river 829",
                "violet river 829" + " ".repeat(8),
                "violet        river",
                " ".repeat(5) + "violet river 829" + " ".repeat(5),
                " ".repeat(3) + "aZ7!mQ2?" + " ".repeat(4),
                " ".repeat(4) + "aZ7!mQ2?x" + " ".repeat(4),
                "a x c p e z q r t");
    }
}
