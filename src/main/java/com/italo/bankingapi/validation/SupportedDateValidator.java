package com.italo.bankingapi.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;

public class SupportedDateValidator implements ConstraintValidator<SupportedDate, LocalDate> {
    public static boolean isSupported(LocalDate value) {
        return value == null || (value.getYear() >= 1 && value.getYear() <= 9999);
    }

    @Override
    public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
        return isSupported(value);
    }
}
