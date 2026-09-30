package com.eduplatform.eduplatform_backend.common.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cleans the HTML the dashboard's rich-text editor writes — a course's description, requirements
 * and learning outcomes, its syllabus items' descriptions, and module and lesson descriptions —
 * down to the tags that editor can produce, before it is stored.
 *
 * <p>These texts are rendered as HTML on the public site and in the dashboard, so storing what a
 * client sent would let anyone who can edit a course put a script or an {@code onerror} handler in
 * front of every visitor. Both frontends sanitise again when they render, with the same allowlist;
 * this is the copy that holds for any other client, and for the API's own responses.
 *
 * <p>The allowlist, shared with the frontends:
 * <ul>
 *   <li>{@code p br h2 h3 h4 strong b em i u s del strike mark sub sup blockquote ul ol li a code
 *       pre hr}, and nothing else: no images, frames, scripts, style elements, classes or ids;</li>
 *   <li>{@code a}: only {@code href}, only http, https or mailto, and always
 *       {@code target="_blank" rel="noopener noreferrer nofollow"}, whatever was sent;</li>
 *   <li>{@code ol}: {@code start}, as a whole number;</li>
 *   <li>{@code style} on {@code p h2 h3 h4} only, reduced to a single
 *       {@code text-align: left|center|right|justify}; any other declaration is dropped.</li>
 * </ul>
 *
 * <p>A value without a single tag is plain text and is returned untouched. Most stored rows predate
 * the editor, and running them through an HTML parser would entity-escape their ampersands and
 * angle brackets, which the readers then show literally. The readers use the same test for a tag.
 */
@Component
public class RichTextSanitizer {

    /**
     * Any opening or closing tag: {@code /<\/?[a-z][^>]*>/i}, the rule both frontends use to decide
     * whether a stored value is HTML or plain text.
     */
    private static final Pattern TAG = Pattern.compile("</?[a-z][^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Set<String> ALIGNMENTS = Set.of("left", "center", "right", "justify");

    /** An {@code ol start}: a whole number, which is all the attribute can mean. */
    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?\\d{1,9}");

    /** Built once and never modified afterwards, so the cleans running on request threads only read it. */
    private static final Safelist ALLOWLIST = new Safelist()
            .addTags("p", "br", "h2", "h3", "h4", "strong", "b", "em", "i", "u", "s", "del", "strike",
                    "mark", "sub", "sup", "blockquote", "ul", "ol", "li", "a", "code", "pre", "hr")
            .addAttributes("a", "href")
            // Also drops a relative href: with no base URI there is nothing to resolve it against,
            // and the editor only ever writes absolute links.
            .addProtocols("a", "href", "http", "https", "mailto")
            .addEnforcedAttribute("a", "target", "_blank")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .addAttributes("ol", "start")
            // Allowed through here and then narrowed to text-align in reduceStyles: the safelist can
            // name an attribute but has no say over what its value contains.
            .addAttributes("p", "style")
            .addAttributes("h2", "style")
            .addAttributes("h3", "style")
            .addAttributes("h4", "style");

    /**
     * The value as it may be stored: null and plain text unchanged, HTML cleaned to the allowlist,
     * and HTML with no text left in it — an emptied editor sends {@code <p></p>} — as "".
     */
    public String sanitize(String value) {
        if (value == null || !TAG.matcher(value).find()) {
            return value;
        }
        Document clean = new Cleaner(ALLOWLIST).clean(Jsoup.parseBodyFragment(value));
        // Pretty-printing re-indents the markup and adds line breaks, so the stored text would
        // differ from what the editor sent even where nothing was removed.
        clean.outputSettings().prettyPrint(false);
        reduceStyles(clean);
        for (Element list : clean.body().select("ol[start]")) {
            if (!WHOLE_NUMBER.matcher(list.attr("start").trim()).matches()) {
                list.removeAttr("start");
            }
        }
        if (isBlank(clean.body().text())) {
            return "";
        }
        return clean.body().html();
    }

    /** Keeps a style attribute only as a valid {@code text-align}, the one the editor writes. */
    private static void reduceStyles(Document clean) {
        for (Element styled : clean.body().select("[style]")) {
            String alignment = textAlign(styled.attr("style"));
            if (alignment == null) {
                styled.removeAttr("style");
            } else {
                styled.attr("style", "text-align: " + alignment);
            }
        }
    }

    /**
     * The alignment a style attribute sets, or null. The last valid declaration wins, as it would in
     * a browser; values are matched as whole keywords, so nothing else can ride along with one.
     */
    private static String textAlign(String style) {
        String alignment = null;
        for (String declaration : style.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) continue;
            String property = declaration.substring(0, colon).trim();
            String value = declaration.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            if (property.equalsIgnoreCase("text-align") && ALIGNMENTS.contains(value)) {
                alignment = value;
            }
        }
        return alignment;
    }

    /**
     * Blank including the non-breaking and other Unicode spaces an editor leaves behind, which
     * {@link String#isBlank()} counts as text: a paragraph holding only {@code &nbsp;} is empty.
     */
    private static boolean isBlank(String text) {
        return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }
}
