package com.eduplatform.eduplatform_backend.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eduplatform.eduplatform_backend.common.error.GlobalExceptionHandler;
import com.eduplatform.eduplatform_backend.review.domain.CourseReview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wrapped form of an unknown sort property. No endpoint reachable over HTTP produces it today
 * (the reviews endpoint in ErrorMappingTest raises the bare PropertyReferenceException), yet it is
 * the half of the rule that also decides what stays a 500: only a PropertyReferenceException in
 * the cause chain is the client's mistake, and every other InvalidDataAccessApiUsageException is
 * our defect and must stay loud. The exceptions go through Spring MVC's own exception resolution
 * against the real advice, so the {@code @ExceptionHandler} mapping is under test, not just the method.
 */
class DataAccessMisuseMappingTest {

    private final FailingController controller = new FailingController();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    /** Captures the handler's own logger, detached from the console so the expected ERROR is not build noise. */
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private Logger handlerLogger;
    private boolean additive;

    @BeforeEach
    void recordHandlerLog() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        additive = handlerLogger.isAdditive();
        handlerLogger.setAdditive(false);
        logEvents.setContext(handlerLogger.getLoggerContext());
        logEvents.start();
        handlerLogger.addAppender(logEvents);
    }

    @AfterEach
    void restoreHandlerLog() {
        handlerLogger.detachAppender(logEvents);
        handlerLogger.setAdditive(additive);
        logEvents.stop();
    }

    @Test
    void anUnknownSortPropertyWrappedAnywhereInTheCauseChainIs400InvalidSortProperty() throws Exception {
        controller.failure = new InvalidDataAccessApiUsageException("translated",
                new IllegalArgumentException("query creation failed", unknownProperty("bogus")));

        mvc.perform(get(FailingController.PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"))
                .andExpect(jsonPath("$.message").value(containsString("'bogus'")))
                // The exception's own message names the entity class; the answer must not.
                .andExpect(jsonPath("$.message").value(not(containsString(CourseReview.class.getSimpleName()))));

        assertThat(loggedAtError()).isEmpty();
    }

    @Test
    void anyOtherDataAccessMisuseKeepsThe500AndTheErrorLog() throws Exception {
        controller.failure = new InvalidDataAccessApiUsageException("misuse",
                new IllegalStateException("no transaction in progress"));

        mvc.perform(get(FailingController.PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(loggedAtError()).hasSize(1);
    }

    private static PropertyReferenceException unknownProperty(String name) {
        return new PropertyReferenceException(name, TypeInformation.of(CourseReview.class), List.of());
    }

    private List<ILoggingEvent> loggedAtError() {
        return logEvents.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
    }

    /** Stands in for a repository call on a {@code Pageable} endpoint: raises whatever the test sets. */
    @RestController
    static class FailingController {

        static final String PATH = "/api/public/courses/any/reviews";

        RuntimeException failure;

        @GetMapping(PATH)
        void fail() {
            throw failure;
        }
    }
}
