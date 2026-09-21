package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;

/** Enforces {@link HttpUrl}. */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
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
