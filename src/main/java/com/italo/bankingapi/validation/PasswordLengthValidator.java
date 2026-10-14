package com.italo.bankingapi.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class PasswordLengthValidator implements ConstraintValidator<PasswordLength, String> {
    private int minCharacters;

    @Override
    public void initialize(PasswordLength constraint) {
        minCharacters = constraint.minCharacters();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Required values are checked separately by @NotBlank.
        if (value == null || value.isBlank()) return true;
        // Every UTF-16 code unit needs at least one byte in valid UTF-8 input.
        if (value.length() > 72) return false;
        return value.codePointCount(0, value.length()) >= minCharacters
                && value.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
