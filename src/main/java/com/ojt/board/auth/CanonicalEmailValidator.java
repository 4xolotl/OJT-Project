package com.ojt.board.auth;

import com.ojt.board.user.EmailCanonicalizer;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class CanonicalEmailValidator implements ConstraintValidator<CanonicalEmail, String> {

    private int maxLength;

    @Override
    public void initialize(CanonicalEmail constraint) {
        maxLength = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || EmailCanonicalizer.isValidCanonical(value, maxLength);
    }
}
