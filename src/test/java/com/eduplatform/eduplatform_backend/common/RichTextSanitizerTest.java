package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.common.html.RichTextSanitizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rich-text allowlist, without starting anything. What is pinned is what would put script in
 * front of a visitor if it slipped, and what would corrupt the texts the dashboard's editor or an
 * older plain-text row legitimately holds.
 */
class RichTextSanitizerTest {

    private final RichTextSanitizer sanitizer = new RichTextSanitizer();

    @Test
    void scriptElementsAndTheirContentAreRemoved() {
        assertThat(sanitizer.sanitize("<p>Hello</p><script>alert(document.cookie)</script>"))
                .isEqualTo("<p>Hello</p>");
        assertThat(sanitizer.sanitize("<script>alert(1)</script>")).isEmpty();
    }

    @Test
    void eventHandlersAndDisallowedElementsGo() {
        assertThat(sanitizer.sanitize("<p onclick=\"alert(1)\">Hi <img src=x onerror=alert(1)></p>"))
                .isEqualTo("<p>Hi </p>");
        assertThat(sanitizer.sanitize("<p>a<iframe src=\"https://evil.example\"></iframe>b</p>"))
                .isEqualTo("<p>ab</p>");
        // class and id are not on the list either; a disallowed wrapper keeps its text.
        assertThat(sanitizer.sanitize("<div class=\"x\" id=\"y\"><span>kept</span></div>")).isEqualTo("kept");
    }

    @Test
    void aJavascriptLinkLosesItsHrefAndEveryLinkOpensSafely() {
        assertLink(sanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>"), null, "x");
        assertLink(sanitizer.sanitize("<a href=\"JaVaScRiPt:alert(1)\" target=\"_self\" rel=\"opener\">x</a>"),
                null, "x");
        assertLink(sanitizer.sanitize("<a href=\"data:text/html,<b>x</b>\">d</a>"), null, "d");
        String kept = sanitizer.sanitize("<p><a href=\"https://aztu.edu.az\" title=\"t\">AzTU</a></p>");
        assertThat(kept).startsWith("<p><a ").endsWith(">AzTU</a></p>").doesNotContain("title");
        assertLink(kept, "https://aztu.edu.az", "AzTU");
        assertLink(sanitizer.sanitize("<a href=\"mailto:info@aztu.edu.az\">mail</a>"), "mailto:info@aztu.edu.az", "mail");
    }

    /**
     * The one link in {@code html}: exactly the expected href (none for null), and the enforced
     * target and rel whatever was sent. Attributes are compared as a set; their order means nothing.
     */
    private static void assertLink(String html, String href, String text) {
        Element a = Jsoup.parseBodyFragment(html).selectFirst("a");
        assertThat(a).as(html).isNotNull();
        Map<String, String> attributes = new HashMap<>();
        a.attributes().forEach(attribute -> attributes.put(attribute.getKey(), attribute.getValue()));
        Map<String, String> expected = new HashMap<>(Map.of("target", "_blank", "rel", "noopener noreferrer nofollow"));
        if (href != null) expected.put("href", href);
        assertThat(attributes).as(html).isEqualTo(expected);
        assertThat(a.text()).isEqualTo(text);
    }

    @Test
    void styleIsReducedToTextAlignOnParagraphsAndHeadings() {
        assertThat(sanitizer.sanitize("<p style=\"text-align: center; color: red\">c</p>"))
                .isEqualTo("<p style=\"text-align: center\">c</p>");
        assertThat(sanitizer.sanitize("<h2 style=\"TEXT-ALIGN:Right\">r</h2>"))
                .isEqualTo("<h2 style=\"text-align: right\">r</h2>");
        assertThat(sanitizer.sanitize("<p style=\"background:url(javascript:alert(1))\">x</p>"))
                .isEqualTo("<p>x</p>");
        assertThat(sanitizer.sanitize("<p style=\"text-align: center; position: fixed\">x</p>"))
                .isEqualTo("<p style=\"text-align: center\">x</p>");
        // Not an alignment keyword, and not an element that may carry a style at all.
        assertThat(sanitizer.sanitize("<p style=\"text-align: expression(alert(1))\">x</p>")).isEqualTo("<p>x</p>");
        assertThat(sanitizer.sanitize("<li style=\"text-align: center\">x</li>")).isEqualTo("<li>x</li>");
    }

    @Test
    void theEditorsOwnMarkupSurvivesUnchanged() {
        String html = "<h2>Plan</h2><p>Some <strong>bold</strong>, <em>italic</em>, <u>underlined</u>, "
                + "<s>struck</s>, <mark>marked</mark>, H<sub>2</sub>O and x<sup>2</sup>.</p>"
                + "<ol start=\"3\"><li><p>three</p></li></ol><ul><li>item</li></ul>"
                + "<blockquote><p>quote</p></blockquote><pre><code>let x = 1;</code></pre><hr><p>a<br>b</p>";
        assertThat(sanitizer.sanitize(html)).isEqualTo(html);
        assertThat(sanitizer.sanitize("<ol start=\"x\"><li>a</li></ol>")).isEqualTo("<ol><li>a</li></ol>");
    }

    @Test
    void plainTextIsStoredAsIs() {
        String legacy = "Line one\nLine two & more: 3 < 5, \"quoted\"";
        assertThat(sanitizer.sanitize(legacy)).isSameAs(legacy);
        assertThat(sanitizer.sanitize("I <3 Python")).isEqualTo("I <3 Python");
        assertThat(sanitizer.sanitize("")).isEmpty();
        assertThat(sanitizer.sanitize(null)).isNull();
    }

    @Test
    void htmlWithNoTextLeftIsStoredAsEmpty() {
        assertThat(sanitizer.sanitize("<p></p>")).isEmpty();
        assertThat(sanitizer.sanitize("<p>  </p><p><br></p>")).isEmpty();
        assertThat(sanitizer.sanitize("<p>&nbsp;</p>")).isEmpty();
        assertThat(sanitizer.sanitize("<p><img src=x onerror=alert(1)></p>")).isEmpty();
    }

    @Test
    void textInsideHtmlIsEscapedNotInterpreted() {
        assertThat(sanitizer.sanitize("<p>Tom &amp; Jerry &lt;3</p>")).isEqualTo("<p>Tom &amp; Jerry &lt;3</p>");
        assertThat(sanitizer.sanitize("<p>Azərbaycan dili — şəkil</p>")).isEqualTo("<p>Azərbaycan dili — şəkil</p>");
    }
}
