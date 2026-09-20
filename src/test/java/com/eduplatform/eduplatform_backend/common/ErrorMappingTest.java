package com.eduplatform.eduplatform_backend.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Malformed requests are the client's mistake and get a 4xx with a stable code. Each of these used
 * to fall through to the catch-all handler: a 500 plus an ERROR stack trace that any anonymous
 * caller could produce at will, burying real failures in the logs.
 */
class ErrorMappingTest extends AbstractIntegrationTest {

    /**
     * Log events of the current test method only. OutputCaptureExtension would also hand back the
     * output of earlier methods in the class, which would pin one test's ERROR on the next.
     */
    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private Logger rootLogger;

    @BeforeEach
    void recordLogEvents() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logEvents.setContext(rootLogger.getLoggerContext());
        logEvents.start();
        rootLogger.addAppender(logEvents);
    }

    @AfterEach
    void stopRecordingLogEvents() {
        rootLogger.detachAppender(logEvents);
        logEvents.stop();
    }

    @Test
    void aMissingRequiredParameterIs400MissingParameter() {
        api.get("/api/public/courses/search").send().expectError(400, "MISSING_PARAMETER");

        assertNothingLoggedAtError();
    }

    @Test
    void aJsonEndpointSentPlainTextIs415UnsupportedMediaType() {
        api.post("/api/auth/login")
                .body("text/plain", "{\"email\":\"someone@it.eduplatform.test\",\"password\":\"x\"}")
                .send().expectError(415, "UNSUPPORTED_MEDIA_TYPE");

        assertNothingLoggedAtError();
    }

    @Test
    void anUnparseableContentTypeIs415UnsupportedMediaType() {
        api.post("/api/auth/login")
                .body("this is not a media type", "{\"email\":\"someone@it.eduplatform.test\",\"password\":\"x\"}")
                .send().expectError(415, "UNSUPPORTED_MEDIA_TYPE");

        assertNothingLoggedAtError();
    }

    /** Nothing on the classpath writes XML, so this endpoint has no acceptable representation. */
    @Test
    void anUnsatisfiableAcceptHeaderIs406NotAcceptable() {
        api.get("/api/public/courses").header("Accept", "application/xml")
                .send().expectError(406, "NOT_ACCEPTABLE");

        assertNothingLoggedAtError();
    }

    /**
     * Spring Security's firewall rejects a ;jsessionid path parameter with a 400 before MVC runs,
     * which Tomcat forwards to /error. That forward passes through the security chain again, and
     * while /error required authentication every such rejection surfaced as 401 on path /error.
     */
    @Test
    void anErrorRaisedOutsideMvcKeepsItsOwnStatus() {
        int status = api.get("/api/public/courses;jsessionid=x").send().status();

        assertThat(status).isEqualTo(400);
    }

    /**
     * Only the container's own ERROR dispatch to /error is let through. Permitting the path let
     * anyone GET it directly and receive an empty error page as a 500 (status 999), a datapoint
     * that 5xx alerting counts, at will and anonymously.
     */
    @Test
    void aDirectRequestForTheErrorPageIsTheOrdinary401() {
        ApiClient.Response response = api.get("/error").send();

        assertThat(response.status()).as("GET /error, body: %s", response.body()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
    }

    /**
     * Endpoints that pass the Pageable straight to a repository leave sort validation to Spring
     * Data, whose PropertyReferenceException, raw or wrapped in InvalidDataAccessApiUsageException,
     * used to reach the catch-all handler. The code is the one the catalogue already answers with.
     */
    @Test
    void anUnknownSortPropertyOnAPagedPublicEndpointIs400InvalidSortProperty() {
        String reviews = "/api/public/courses/" + publishedCourse(true).id() + "/reviews";
        forgetLogEvents();

        api.get(reviews).query("sort", "bogus").send().expectError(400, "INVALID_SORT_PROPERTY");
        api.get(reviews).query("sort", "bogus,desc").send().expectError(400, "INVALID_SORT_PROPERTY");
        // A property the entity has still sorts.
        api.get(reviews).query("sort", "rating,desc").send().expectStatus(200);

        assertNothingLoggedAtError();
    }

    /** Drops what the test's own setup logged, so only the requests under test are judged. */
    private void forgetLogEvents() {
        synchronized (logEvents) {
            logEvents.list.clear();
        }
    }

    private void assertNothingLoggedAtError() {
        List<String> errors;
        // AppenderBase.doAppend synchronises on the appender, so reading under the same lock sees
        // every event the request thread appended.
        synchronized (logEvents) {
            errors = logEvents.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                    .map(event -> event.getLoggerName() + ": " + event.getFormattedMessage())
                    .toList();
        }
        assertThat(errors).as("events logged at ERROR").isEmpty();
    }
}
