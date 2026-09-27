package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client address that is not an IP address never breaks a request. Tomcat's RemoteIpValve takes
 * the forwarded address as whatever text it holds; stored in the INET columns of the audit log,
 * the refresh tokens and the security events, a malformed one failed the insert, and sign-in,
 * refresh, logout and every audited admin write answered 400 "cannot be stored". The request now
 * carries the TCP peer instead.
 *
 * <p>The test client connects over loopback, which Tomcat trusts as a proxy, so whatever it puts
 * in X-Forwarded-For is taken as the client address, as a trusted proxy's header is.
 */
class ClientAddressTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"10.63.0.1743", "2001:db8::zz", "not-an-address", "10.230.0.1/24"})
    void signInRefreshAndLogOutWorkWhateverTheForwardedAddressSays(String forwarded) {
        TestUser user = newUser("odd-address", "USER");

        JsonNode tokens = api.post("/api/auth/login").header("X-Forwarded-For", forwarded)
                .json(Json.object("email", user.email(), "password", PASSWORD)).send().expectStatus(200).data();
        JsonNode refreshed = api.post("/api/auth/refresh").header("X-Forwarded-For", forwarded)
                .json(Json.object("refreshToken", tokens.path("refreshToken").asText())).send()
                .expectStatus(200).data();
        api.post("/api/auth/logout").header("X-Forwarded-For", forwarded)
                .json(Json.object("refreshToken", refreshed.path("refreshToken").asText())).send().expectStatus(204);
        api.post("/api/auth/login").header("X-Forwarded-For", forwarded)
                .json(Json.object("email", user.email(), "password", "wrong-password")).send()
                .expectError(401, "INVALID_CREDENTIALS");

        assertThat(jdbc.queryForList("select distinct host(ip_address) from refresh_tokens where user_id = ?",
                String.class, user.id())).allMatch(ClientAddressTest::isLoopback);
    }

    @Test
    void anAuditedAdminWriteWorksAndRecordsThePeer() {
        String adminToken = newAdminToken();
        String slug = unique("odd-address");

        UUID category = UUID.fromString(api.post("/api/admin/categories").bearer(adminToken)
                .header("X-Forwarded-For", "10.63.0.1743")
                .json(Json.object("slug", slug, "name", "Category " + slug, "sortOrder", 0, "active", true))
                .send().expectStatus(201).data().path("id").asText());

        String recorded = jdbc.queryForObject(
                "select host(ip_address) from audit_logs where entity_type = 'CATEGORY' and entity_id = ?",
                String.class, category);
        assertThat(isLoopback(recorded)).as("the peer, not the malformed header: %s", recorded).isTrue();
    }

    /** A well-formed forwarded address is still what counts. */
    @Test
    void aValidForwardedAddressIsStillUsed() {
        String adminToken = newAdminToken();
        String slug = unique("good-address");

        UUID category = UUID.fromString(api.post("/api/admin/categories").bearer(adminToken)
                .header("X-Forwarded-For", "10.230.9.9")
                .json(Json.object("slug", slug, "name", "Category " + slug, "sortOrder", 0, "active", true))
                .send().expectStatus(201).data().path("id").asText());

        assertThat(jdbc.queryForObject(
                "select host(ip_address) from audit_logs where entity_type = 'CATEGORY' and entity_id = ?",
                String.class, category)).isEqualTo("10.230.9.9");
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address) || "::1".equals(address);
    }
}
