package com.eduplatform.eduplatform_backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application on a random port against a throwaway PostgreSQL 16, under the dev
 * profile so Flyway also applies classpath:db/dev: the four seeded accounts
 * ({@code user@ / tutor@ / admin@ / superadmin@eduplatform.local}, all {@value #PASSWORD}).
 * V1 creates pgcrypto, citext and btree_gist itself, which works because the container's user
 * is a superuser.
 *
 * <p>One container per JVM, started in a static initializer and never stopped here; Ryuk removes
 * it when the JVM exits. {@code @Testcontainers}/{@code @Container} would stop it after each test
 * class while Spring's context cache keeps that class's application context, and its connection
 * pool, alive for the next class with the same configuration.
 *
 * <p>Every test class shares the database, so each test creates the users and courses it asserts
 * on, under unique emails and slugs, and never counts rows it did not create. Users are inserted
 * with SQL rather than through the API, so that a test about, say, course search does not fail
 * because registration is broken; the registration tests drive the API instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        // application.properties imports ./.env, and Maven runs tests from the repo root, so a
        // developer's real settings would otherwise reach this context: a deployment .env sets
        // SPRING_PROFILES_ACTIVE=prod and STORAGE_LOCAL_DIR=/opt/uploads (not writable here), and
        // can hold SMTP and OAuth credentials. Everything that changes behaviour is pinned below;
        // the datasource and upload directory are set in wireContainer().
        "app.ratelimit.enabled=false",
        "app.mail.enabled=false",
        "app.payments.enabled=false",
        "app.security.jwt.access-secret=integration-test-signing-key-not-used-anywhere-else-0123456789",
        "app.oauth.google.client-id=",
        "app.oauth.facebook.app-id=",
        // True on purpose. With the dev seeds there is always an admin, so admin self-registration
        // must stay closed even with the flag on: the flag alone used to be enough to open it.
        "app.security.admin-self-register-enabled=true",
        // Up to three application contexts (this one, the rate-limit one, the mail one) stay cached
        // for the whole run, all on one Postgres.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
})
public abstract class AbstractIntegrationTest {

    /** The password of the dev seed accounts, reused for every account the tests create. */
    protected static final String PASSWORD = "Password123!";

    @SuppressWarnings("resource") // outlives every test class by design; see the class comment
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("eduplatform")
                    .withUsername("eduplatform")
                    .withPassword("eduplatform");

    private static final Path UPLOAD_DIR;

    /** bcrypt at cost 12 takes a noticeable fraction of a second, so it is computed once per JVM. */
    private static volatile String passwordHash;

    static {
        POSTGRES.start();
        try {
            UPLOAD_DIR = Files.createTempDirectory("eduplatform-test-uploads");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void wireContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.storage.local.base-dir", UPLOAD_DIR::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ApiClient api;

    @BeforeEach
    protected void createClient() {
        api = new ApiClient(port, objectMapper);
    }

    // ── fixtures ────────────────────────────────────────────────────────

    public record TestUser(UUID id, String email) {}

    public record TestTutor(UUID userId, String email, UUID profileId) {}

    public record CourseRef(UUID id, String slug, String title) {}

    /** {@code label} plus a random suffix; lowercase letters, digits and hyphens only, so it is also a valid slug. */
    protected static String unique(String label) {
        return label + "-" + word();
    }

    /**
     * A random token that the full-text parser keeps as a single word, so a test can find the one
     * course it made through a catalogue search shared with every other test.
     */
    protected static String word() {
        return "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected static String uniqueEmail(String label) {
        return unique(label) + "@it.eduplatform.test";
    }

    /** An ACTIVE account holding exactly {@code roles} (RoleCode names), password {@value #PASSWORD}. */
    protected TestUser newUser(String label, String... roles) {
        UUID id = UUID.randomUUID();
        String email = uniqueEmail(label);
        jdbc.update("""
                insert into users (id, email, password_hash, first_name, last_name, status, email_verified_at, locale)
                values (?, ?, ?, 'Test', ?, 'ACTIVE', now(), 'en')
                """, id, email, passwordHash(), label);
        for (String role : roles) {
            int linked = jdbc.update("""
                    insert into user_roles (user_id, role_id, granted_at)
                    select ?, r.id, now() from roles r where r.code = ?
                    """, id, role);
            assertThat(linked).as("role %s is seeded by V2", role).isEqualTo(1);
        }
        return new TestUser(id, email);
    }

    /**
     * A TUTOR with an APPROVED profile. Course creation needs both: the role grants
     * {@code course:create}, and the service also requires an approved profile.
     */
    protected TestTutor newApprovedTutor(String label) {
        TestUser user = newUser(label, "TUTOR");
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into tutor_profiles (id, user_id, headline, approval_status, approved_at)
                values (?, ?, 'Integration-test tutor', 'APPROVED', now())
                """, profileId, user.id());
        return new TestTutor(user.id(), user.email(), profileId);
    }

    /** Role codes as stored, read straight from the database rather than from any API response. */
    protected Set<String> rolesInDb(UUID userId) {
        return new HashSet<>(jdbc.queryForList("""
                select r.code from user_roles ur join roles r on r.id = ur.role_id where ur.user_id = ?
                """, String.class, userId));
    }

    private String passwordHash() {
        String hash = passwordHash;
        if (hash == null) {
            hash = passwordEncoder.encode(PASSWORD);
            passwordHash = hash;
        }
        return hash;
    }

    // ── auth ────────────────────────────────────────────────────────────

    protected ApiClient.Response loginResponse(String email, String password) {
        return api.post("/api/auth/login").json(Json.object("email", email, "password", password)).send();
    }

    /** Logs in with {@value #PASSWORD} and returns the access token. */
    protected String login(String email) {
        return loginResponse(email, PASSWORD).expectStatus(200).data().path("accessToken").asText();
    }

    /** A fresh ADMIN's access token, so no test depends on the state of the seeded admin. */
    protected String newAdminToken() {
        return login(newUser("admin", "ADMIN").email());
    }

    // ── courses ─────────────────────────────────────────────────────────

    /**
     * A valid create-course body for an ONLINE course. The title is "Course " plus a random
     * {@link #word()}, which a catalogue search for that word finds. The map is mutable so a test
     * can override single fields.
     */
    protected static Map<String, Object> courseRequest(String label, boolean free) {
        String token = word();
        return Json.object(
                "slug", label + "-" + token,
                "title", "Course " + token,
                "subtitle", "Integration test " + label,
                "courseType", "ONLINE",
                "level", "BEGINNER",
                "language", "en",
                "free", free,
                "price", free ? BigDecimal.ZERO : new BigDecimal("49.99"),
                "currency", "AZN",
                "categoryIds", List.of(),
                "onlineDetails", Json.object("totalVideoSeconds", 3600, "hasCertificate", false, "dripEnabled", false));
    }

    protected CourseRef createCourse(String tutorToken, Map<String, Object> request) {
        JsonNode course = api.post("/api/portal/courses").bearer(tutorToken).json(request)
                .send().expectStatus(201).data();
        return new CourseRef(UUID.fromString(course.path("id").asText()),
                course.path("slug").asText(), course.path("title").asText());
    }

    protected void submitForReview(String tutorToken, UUID courseId) {
        api.post("/api/portal/courses/" + courseId + "/submit").bearer(tutorToken).send().expectStatus(200);
    }

    /** DRAFT to PUBLISHED the way it happens in production: the tutor submits, an admin approves. */
    protected void publish(String tutorToken, UUID courseId) {
        submitForReview(tutorToken, courseId);
        api.post("/api/admin/courses/" + courseId + "/decision").bearer(newAdminToken())
                .json(Json.object("decision", "APPROVED"))
                .send().expectStatus(200);
    }

    /** A PUBLISHED course owned by a fresh tutor. */
    protected CourseRef publishedCourse(boolean free) {
        TestTutor tutor = newApprovedTutor("tutor");
        String token = login(tutor.email());
        CourseRef course = createCourse(token, courseRequest(free ? "free" : "paid", free));
        publish(token, course.id());
        return course;
    }

    // ── media ───────────────────────────────────────────────────────────

    protected ApiClient.Response upload(String token, String filename, String contentType, byte[] content) {
        return api.post("/api/media").bearer(token).file("file", filename, contentType, content).send();
    }

    protected UUID uploadMedia(String token, String filename, String contentType, byte[] content) {
        return UUID.fromString(upload(token, filename, contentType, content)
                .expectStatus(201).data().path("id").asText());
    }
}
