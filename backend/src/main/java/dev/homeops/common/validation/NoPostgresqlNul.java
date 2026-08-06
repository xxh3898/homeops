package dev.homeops.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = NoPostgresqlNul.Validator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoPostgresqlNul {
    String message() default "must not contain a NUL character";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<NoPostgresqlNul, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.indexOf('\0') < 0;
        }
    }
}
