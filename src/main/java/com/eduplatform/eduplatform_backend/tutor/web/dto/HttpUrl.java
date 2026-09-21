package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An absolute {@code http://} or {@code https://} address with a host.
 *
 * <p>These values end up as {@code href}s on the public expert page, so the scheme is the point
 * of the check: {@code javascript:} or {@code data:} there would run script for every visitor.
 * Null and blank pass, because on a profile edit they mean "leave it" and "clear it".
 */
@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "must be a web address starting with http:// or https://";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
