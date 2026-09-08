package com.ojt.board.auth;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordByteLengthValidator implements ConstraintValidator<PasswordByteLength, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null/blank validation belongs to @NotBlank. BCrypt limits bytes, not characters.
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
