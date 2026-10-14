package com.italo.bankingapi.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target(FIELD)
@Retention(RUNTIME)
@Constraint(validatedBy = PasswordLengthValidator.class)
public @interface PasswordLength {
    String message() default "Password must have at most 72 UTF-8 bytes.";
    int minCharacters() default 0;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
