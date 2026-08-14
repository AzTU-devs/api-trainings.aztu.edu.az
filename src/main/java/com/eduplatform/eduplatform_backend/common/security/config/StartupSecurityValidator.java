package com.eduplatform.eduplatform_backend.common.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Refuses to start when the app is configured in a way that is fine locally but wrong
 * once deployed. Every check is a no-op under the {@code dev} profile.
 *
 * <p>Implemented as a {@link BeanFactoryPostProcessor} rather than an ordinary bean so it
 * runs <em>before</em> any singleton is created — in particular before {@code flywayInitializer}.
 * A deploy with a bad secret or a missing DSN therefore fails on a readable message instead
 * of a driver-level "claims to not accept jdbcUrl, ${DATABASE_URL}", and never gets far
 * enough to run migrations.
 *
 * <p>Checks:
 * <ul>
 *   <li>Required settings are actually present and resolvable.</li>
 *   <li>JWT signing secret is neither a placeholder nor shorter than HS256 requires.</li>
 *   <li>The Flyway path excludes {@code db/dev}, which seeds login-ready ADMIN and
 *       SUPER_ADMIN accounts sharing the password {@code Password123!}.</li>
 * </ul>
 */
@Component
public class StartupSecurityValidator implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityValidator.class);

    /** HS256 needs at least 256 bits of key material. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String DEV_SEED_LOCATION = "db/dev";

    /** property -> the environment variable a deployer is expected to set for it. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("spring.datasource.url", "DATABASE_URL");
        REQUIRED.put("spring.datasource.username", "DATABASE_USERNAME");
        REQUIRED.put("app.security.jwt.access-secret", "JWT_ACCESS_SECRET");
    }

    /**
     * Set in {@link #postProcessBeanFactory}, not injected: a BeanFactoryPostProcessor is
     * instantiated before {@code AutowiredAnnotationBeanPostProcessor} is active, so
     * constructor injection would fail with "No default constructor found". The
     * Environment is registered as a plain singleton by then, so it can just be looked up.
     */
    private Environment env;

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        this.env = beanFactory.getBean(
                ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME, Environment.class);

        if (Arrays.asList(env.getActiveProfiles()).contains("dev")) {
            return;
        }

        requireSettings();
        checkJwtSecret(resolve("app.security.jwt.access-secret"));
        checkFlywayLocations(resolve("spring.flyway.locations"));
        warnOnLocalhostCors(resolve("app.cors.allowed-origins"));
    }

    /** Reports every missing setting at once — one redeploy per fix is a slow way to learn. */
    private void requireSettings() {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, envVar) -> {
            if (!StringUtils.hasText(resolve(property))) {
                missing.add("  - " + property + "   (set " + envVar + ")");
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: required configuration is missing outside the dev profile.\n"
                            + String.join("\n", missing)
                            + "\nThese have no defaults on purpose, so a misconfigured deploy fails here "
                            + "instead of silently connecting somewhere unintended.");
        }
        if (!StringUtils.hasText(resolve("spring.datasource.password"))) {
            log.warn("spring.datasource.password is empty. Correct only if the database uses IAM or "
                    + "certificate auth; otherwise set DATABASE_PASSWORD.");
        }
    }

    private void checkJwtSecret(String accessSecret) {
        String secret = accessSecret == null ? "" : accessSecret.trim();
        String lower = secret.toLowerCase(Locale.ROOT);
        if (lower.contains("change-me") || lower.contains("dev-only") || lower.contains("insecure")) {
            throw new IllegalStateException(
                    "Refusing to start: app.security.jwt.access-secret is still a placeholder value. "
                            + "Set JWT_ACCESS_SECRET to a strong random value, e.g. `openssl rand -base64 48`.");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "Refusing to start: app.security.jwt.access-secret is only " + bytes
                            + " bytes; HS256 requires at least " + MIN_SECRET_BYTES
                            + ". Generate one with `openssl rand -base64 48`.");
        }
    }

    private void checkFlywayLocations(String flywayLocations) {
        if (flywayLocations != null && flywayLocations.contains(DEV_SEED_LOCATION)) {
            throw new IllegalStateException(
                    "Refusing to start: spring.flyway.locations includes '" + DEV_SEED_LOCATION
                            + "', which seeds ADMIN/SUPER_ADMIN accounts with a shared well-known password. "
                            + "Dev seeds must never be applied outside the dev profile.");
        }
    }

    private void warnOnLocalhostCors(String corsOrigins) {
        if (corsOrigins != null && corsOrigins.contains("localhost")) {
            log.warn("app.cors.allowed-origins still contains a localhost entry outside the dev profile: {}. "
                    + "Set CORS_ALLOWED_ORIGINS to the real frontend origins.", corsOrigins);
        }
    }

    /**
     * Unset placeholders (e.g. {@code ${DATABASE_URL}} with no value) make
     * {@code getProperty} throw rather than return null; treat that as "not configured"
     * so it is reported alongside every other missing setting.
     */
    private String resolve(String property) {
        try {
            return env.getProperty(property);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return null;
        }
    }
}
