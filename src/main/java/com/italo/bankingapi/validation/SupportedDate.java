package com.italo.bankingapi.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(FIELD)
@Retention(RUNTIME)
@Constraint(validatedBy = SupportedDateValidator.class)
public @interface SupportedDate {
    String message() default "Date must be between 0001-01-01 and 9999-12-31.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
