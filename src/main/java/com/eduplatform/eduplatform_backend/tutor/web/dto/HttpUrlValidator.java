package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;

/** Enforces {@link HttpUrl}. */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || isWebAddress(value);
    }

    /** The rule itself, for code that has to check a value no request annotation covered. */
    public static boolean isWebAddress(String value) {
        if (value == null) {
            return false;
        }
        URI uri;
        try {
            // Trimmed because the service stores the trimmed value; this validates what is kept.
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                // No host means an opaque or relative form ("https:foo"), not an address.
                && uri.getHost() != null
                // "https://linkedin.com@evil.example" reads as LinkedIn and goes elsewhere; no
                // profile link has a reason to carry credentials.
                && uri.getRawUserInfo() == null;
    }
}
