package com.eduplatform.eduplatform_backend.tutor;

import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.tutor.service.CustomExpertise;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The normalisation and limits every write of an expert's own areas goes through, without
 * starting anything. The API paths that use it are covered by CustomExpertiseTest.
 */
class CustomExpertiseRulesTest {

    @Test
    void labelsAreTrimmedAndTheirWhitespaceCollapsed() {
        assertThat(CustomExpertise.normalize(List.of("  Machine \t  learning ", "Data  science"), List.of()))
                .containsExactly("Machine learning", "Data science");
    }

    @Test
    void emptiesAndCaseInsensitiveRepeatsAreDroppedKeepingTheFirstSpelling() {
        List<String> raw = Arrays.asList("Robotics", "", "   ", null, "ROBOTICS", "robotics ", "Welding");
        assertThat(CustomExpertise.normalize(raw, List.of())).containsExactly("Robotics", "Welding");
        // The Azerbaijani dotted and dotless i fold together with i and I.
        assertThat(CustomExpertise.normalize(List.of("İnformatika", "informatika", "INFORMATİKA"), List.of()))
                .containsExactly("İnformatika");
    }

    @Test
    void aLabelThatNamesASelectedCategoryIsDropped() {
        assertThat(CustomExpertise.normalize(List.of("information technology", "Cyber security"),
                List.of("Information  Technology")))
                .containsExactly("Cyber security");
    }

    @Test
    void theLimitsApplyAfterNormalisation() {
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add("Area " + i);
        assertThatThrownBy(() -> CustomExpertise.normalize(eleven, List.of())).satisfies(invalid());

        // Eleven sent, ten once the repeat is dropped: accepted.
        List<String> tenAfterDedup = new ArrayList<>(eleven.subList(0, 10));
        tenAfterDedup.add("area 0");
        assertThat(CustomExpertise.normalize(tenAfterDedup, List.of())).hasSize(10);

        assertThatThrownBy(() -> CustomExpertise.normalize(List.of("C"), List.of())).satisfies(invalid());
        assertThatThrownBy(() -> CustomExpertise.normalize(List.of("x".repeat(61)), List.of())).satisfies(invalid());
        assertThat(CustomExpertise.normalize(List.of("  C#  ", "x".repeat(60)), List.of())).hasSize(2);
    }

    @Test
    void onlyPlainTextIsAccepted() {
        assertThatThrownBy(() -> CustomExpertise.normalize(List.of("<b>AI</b>"), List.of())).satisfies(invalid());
        assertThatThrownBy(() -> CustomExpertise.normalize(List.of("a > b"), List.of())).satisfies(invalid());
        assertThatThrownBy(() -> CustomExpertise.normalize(List.of("Bell\u0007"), List.of())).satisfies(invalid());
    }

    @Test
    void anExpertNeedsAnAreaOfEitherKind() {
        assertThatThrownBy(() -> CustomExpertise.requireAny(List.of(), List.of()))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.code()).isEqualTo("EXPERTISE_REQUIRED");
                    assertThat(e.getMessage()).isEqualTo("Choose at least one area of expertise or add your own");
                });
        assertThatCode(() -> CustomExpertise.requireAny(List.of(), List.of("Welding"))).doesNotThrowAnyException();
        assertThatCode(() -> CustomExpertise.requireAny(List.of("category"), List.of())).doesNotThrowAnyException();
    }

    private static java.util.function.Consumer<Throwable> invalid() {
        return e -> assertThat(e).isInstanceOfSatisfying(AppException.class,
                app -> assertThat(app.code()).isEqualTo("INVALID_CUSTOM_EXPERTISE"));
    }
}
