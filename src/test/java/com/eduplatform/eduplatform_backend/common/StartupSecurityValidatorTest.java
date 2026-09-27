package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.common.security.config.StartupSecurityValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up checks, without starting anything: the validator runs against an environment
 * built here. A deployment's configuration is what is under test, so each case is one property
 * away from a valid production setup.
 */
class StartupSecurityValidatorTest {

    @Test
    void aValidProductionConfigurationStarts() {
        assertThatCode(() -> validate(production())).doesNotThrowAnyException();
    }

    /** "prod,dev" used to apply the dev seeds and skip every check, because dev was among the profiles. */
    @Test
    void devTogetherWithAnotherProfileIsRefused() {
        MockEnvironment env = production();
        env.setActiveProfiles("prod", "dev");
        assertThatThrownBy(() -> validate(env)).hasMessageContaining("dev profile is active together");
    }

    @Test
    void devOnItsOwnSkipsTheProductionChecks() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThatCode(() -> validate(env)).doesNotThrowAnyException();
    }

    /** Credentials are allowed for every listed origin, so a wildcard reflected any site with them. */
    @Test
    void aWildcardNullOrPlainHttpCorsOriginIsRefused() {
        for (String origins : new String[]{"*", "https://*.aztu.edu.az", "null", "http://trainings.aztu.edu.az"}) {
            MockEnvironment env = production();
            env.setProperty("app.cors.allowed-origins", origins);
            assertThatThrownBy(() -> validate(env)).as(origins).hasMessageContaining("CORS_ALLOWED_ORIGINS");
        }
    }

    @Test
    void thePlaceholderSecretIsRefusedInEveryProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("app.security.jwt.access-secret", "change-me-to-a-long-random-string-0123456789");
        assertThatThrownBy(() -> validate(env)).hasMessageContaining("change-me");
    }

    private static MockEnvironment production() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db/eduplatform");
        env.setProperty("spring.datasource.username", "eduplatform");
        env.setProperty("spring.datasource.password", "secret");
        env.setProperty("app.security.jwt.access-secret", "a-perfectly-random-production-secret-0123456789abcdef");
        env.setProperty("spring.flyway.locations", "classpath:db/migration");
        env.setProperty("app.cors.allowed-origins",
                "https://trainings.aztu.edu.az,https://dashboard-trainings.aztu.edu.az");
        return env;
    }

    private static void validate(MockEnvironment env) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton(ConfigurableApplicationContext.ENVIRONMENT_BEAN_NAME, env);
        new StartupSecurityValidator().postProcessBeanFactory(factory);
    }
}
