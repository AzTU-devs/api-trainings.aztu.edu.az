package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which browser origins may call the API with the visitor's credentials: exactly the listed
 * ones. Production was found reflecting any Origin, "null" included, back with
 * Access-Control-Allow-Credentials, which let any web page act as its signed-in visitor.
 *
 * <p>The tests' allowed origins are http://localhost:3000, http://localhost:3001 and
 * http://127.0.0.1:3000 (AbstractIntegrationTest); production lists the two https sites.
 */
class CorsPolicyTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/public/categories";

    @Test
    void aListedOriginGetsItselfBackWithCredentials() {
        ApiClient.Response preflight = preflight("http://localhost:3000").expectStatus(200);
        assertThat(preflight.header("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(preflight.header("Access-Control-Allow-Credentials")).contains("true");

        ApiClient.Response get = api.get(PATH).header("Origin", "http://localhost:3000").send().expectStatus(200);
        assertThat(get.header("Access-Control-Allow-Origin")).contains("http://localhost:3000");
    }

    @Test
    void anyOtherOriginIsRefusedAndNotReflected() {
        for (String origin : new String[]{"https://evil.example", "null", "http://localhost:5173",
                "http://localhost:3000.evil.example", "https://trainings.aztu.edu.az.evil.example"}) {
            ApiClient.Response preflight = preflight(origin);
            assertThat(preflight.status()).as(origin).isEqualTo(403);
            assertThat(preflight.header("Access-Control-Allow-Origin")).as(origin).isEmpty();

            ApiClient.Response get = api.get(PATH).header("Origin", origin).send();
            assertThat(get.status()).as(origin).isEqualTo(403);
            assertThat(get.header("Access-Control-Allow-Origin")).as(origin).isEmpty();
        }
    }

    /** Server-side callers (the public site's BFF, health checks) send no Origin and are not CORS requests. */
    @Test
    void aRequestWithoutAnOriginIsNotACorsRequest() {
        ApiClient.Response get = api.get(PATH).send().expectStatus(200);
        assertThat(get.header("Access-Control-Allow-Origin")).isEmpty();
    }

    private ApiClient.Response preflight(String origin) {
        return api.options(PATH)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization")
                .send();
    }
}
