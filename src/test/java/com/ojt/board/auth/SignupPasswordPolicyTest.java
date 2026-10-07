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
            "Mailcreated5240",
            "abcabcabcabcabc",
            "!!!!!!!!!!!!!!!!",
            "123456789012345",
            "qwertyuiopasdfgh",
            "1qaz2wsx3edc4rfv",
            "asdfghjklasdfgh",
            "!\"#$%&'()*+,-./",
            "!@#$%^&*()_+{}|",
            "abcdefghij     ",
            "!@#$%^&*()     ",
            "4xolotlpassword",
            "mnbvcxzlkjhgfds"
    })
    void rejectsBlocklistedAndObviousWholePasswordPatterns(String password) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> policy.validate(EMAIL, password));
        assertEquals(SignupPasswordPolicy.GUESSABLE_MESSAGE, error.getMessage());
        assertFalse(error.getMessage().contains(password));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "person@example.com",
            "John.Doe20262026!",
            "johndoejohndoejohndoe",
            "johnsmithjohnsmith"
    })
    void rejectsEmailAndLimitedPredictableDerivatives(String password) {
        String email = switch (password) {
            case "person@example.com" -> "person@example.com";
            case "johnsmithjohnsmith" -> "john.smith@example.com";
            default -> EMAIL;
        };
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> policy.validate(email, password));
        assertEquals(SignupPasswordPolicy.CONTEXT_MESSAGE, error.getMessage());
        assertFalse(error.getMessage().contains(password));
        assertFalse(error.getMessage().contains(email));
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
}
