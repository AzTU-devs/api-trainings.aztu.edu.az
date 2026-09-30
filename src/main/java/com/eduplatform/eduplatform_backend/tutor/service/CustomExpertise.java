package com.eduplatform.eduplatform_backend.tutor.service;

import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The areas of expertise an expert types in themselves, next to the catalogue categories they pick.
 * They are free-text labels kept on the profile; they never become categories, so the admin-managed
 * list stays what the catalogue filters on.
 *
 * <p>One rule for every path that writes them — sign-up, applying from an existing account, the
 * expert's own edit and an admin's — so a label that one path refuses cannot come in through
 * another:
 * <ol>
 *   <li>each label is trimmed and its runs of whitespace collapsed to one space;</li>
 *   <li>empty labels are dropped, and so is any that repeats an earlier one or names one of the
 *       expert's selected categories, both compared ignoring case; the first spelling is kept;</li>
 *   <li>what is left must be at most {@value #MAX_ENTRIES} labels of {@value #MIN_LENGTH} to
 *       {@value #MAX_LENGTH} characters, in plain text: a label with {@code <}, {@code >} or a
 *       control character is refused rather than cleaned, because it is shown as text on the public
 *       expert page and nobody types those into an area of expertise by accident.</li>
 * </ol>
 * Anything else is a 400 INVALID_CUSTOM_EXPERTISE naming the problem.
 */
public final class CustomExpertise {

    public static final int MAX_ENTRIES = 10;
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 60;

    /** Whitespace in the Unicode sense too: an editor's non-breaking space is still a space. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\p{Z}]+");

    private CustomExpertise() {}

    /**
     * The labels as they are to be stored.
     *
     * @param raw           the labels as sent; null is none
     * @param categoryNames the names of the categories the expert will have once this write is done
     */
    public static List<String> normalize(List<String> raw, Collection<String> categoryNames) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> categories = new HashSet<>();
        for (String name : categoryNames) {
            if (name != null) categories.add(fold(collapse(name)));
        }
        Set<String> seen = new HashSet<>();
        for (String entry : raw) {
            String label = entry == null ? "" : collapse(entry);
            if (label.isEmpty()) continue;
            String key = fold(label);
            if (!seen.add(key) || categories.contains(key)) continue;
            if (label.indexOf('<') >= 0 || label.indexOf('>') >= 0
                    || label.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("Your own areas of expertise must be plain text, without < > or control characters");
            }
            if (label.length() < MIN_LENGTH || label.length() > MAX_LENGTH) {
                throw invalid("Each of your own areas of expertise must be " + MIN_LENGTH + " to " + MAX_LENGTH
                        + " characters long: \"" + label + "\"");
            }
            out.add(label);
        }
        if (out.size() > MAX_ENTRIES) {
            throw invalid("Add at most " + MAX_ENTRIES + " areas of expertise of your own");
        }
        return out;
    }

    /**
     * An expert is filed under at least one area, but it may now be one of their own rather than a
     * category: {@code expertiseCategoryIds} can be empty when the custom list is not.
     */
    public static void requireAny(Collection<?> categories, Collection<String> custom) {
        if (categories.isEmpty() && custom.isEmpty()) {
            throw Errors.badRequest("EXPERTISE_REQUIRED", "Choose at least one area of expertise or add your own");
        }
    }

    private static String collapse(String value) {
        return WHITESPACE_RUN.matcher(value).replaceAll(" ").strip();
    }

    /**
     * A comparison key that ignores case the way {@link String#equalsIgnoreCase} does, character by
     * character, so the Azerbaijani dotted and dotless i fold together with i and I instead of
     * depending on a locale.
     */
    private static String fold(String value) {
        StringBuilder key = new StringBuilder(value.length());
        value.codePoints().forEach(c -> key.appendCodePoint(Character.toLowerCase(Character.toUpperCase(c))));
        return key.toString();
    }

    private static AppException invalid(String message) {
        return Errors.badRequest("INVALID_CUSTOM_EXPERTISE", message);
    }
}
