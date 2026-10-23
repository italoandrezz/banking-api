package com.italo.bankingapi.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({FIELD, PARAMETER, RECORD_COMPONENT, METHOD, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Constraint(validatedBy = {})
@Pattern(regexp = "[^\\x00]*")
@ReportAsSingleViolation
public @interface NoNullCharacter {
    String message() default "Text must not contain the null character (U+0000).";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
