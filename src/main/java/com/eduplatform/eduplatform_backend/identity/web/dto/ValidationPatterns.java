package com.eduplatform.eduplatform_backend.identity.web.dto;

/**
 * Patterns shared by the account DTOs, so that self-signup, password reset and the admin forms
 * cannot drift apart. Each also accepts an empty string: on the admin forms and the profile edit
 * a blank field means "no change" or "none", and the service treats it so.
 */
public final class ValidationPatterns {

    private ValidationPatterns() {}

    /**
     * The self-signup password rule (10-100 characters with an upper-case letter, a lower-case
     * letter and a digit) in one expression. An administrator setting someone's password used to
     * be held to nothing at all, so "a" was accepted for an account that might be an ADMIN's.
     */
    public static final String PASSWORD_OR_BLANK = "^$|^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).{10,100}$";

    public static final String PASSWORD_MESSAGE =
            "Password must be 10-100 characters with an uppercase letter, a lowercase letter and a digit";

    /**
     * Digits with the usual separators and an optional leading +, 7 to 20 characters, of which 7
     * to 15 are digits (15 is the longest international number). The digit count is the
     * lookahead: the character class alone let a value of seven dashes through as a number.
     */
    public static final String PHONE_OR_BLANK =
            "^$|^(?=(?:[^0-9]*[0-9]){7,15}[^0-9]*$)\\+?[0-9 ()\\-]{7,20}$";

    public static final String PHONE_MESSAGE =
            "must be a phone number of 7-15 digits, optionally with a leading +, spaces, dashes or brackets";

    /** The interface languages the site and the dashboard are translated into. */
    public static final String LOCALE_OR_BLANK = "^$|^(az|en|ru)$";

    public static final String LOCALE_MESSAGE = "must be one of az, en, ru";
}
