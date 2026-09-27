package com.eduplatform.eduplatform_backend.audit;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IP blocklist. It had no list and no unblock, accepted any string (a range was stored and
 * then never matched), could block the caller's own address, and a row deleted by hand stayed
 * enforced until a restart.
 *
 * <p>The test client connects over loopback, which Tomcat trusts as a proxy, so X-Forwarded-For
 * decides the client address each request is seen from. Every test uses its own documentation
 * address and unblocks it again, because the blocklist is shared by the whole test run.
 */
class IpBlockTest extends AbstractIntegrationTest {

    @Test
    void aBlockedAddressIsRefusedEverywhereUntilItIsUnblocked() {
        String superToken = login(newUser("super", "SUPER_ADMIN").email());
        String address = "203.0.113." + (10 + (int) (Math.random() * 200));

        block(superToken, address).expectStatus(204);
        api.get("/api/public/categories").header("X-Forwarded-For", address).send()
                .expectError(403, "IP_BLOCKED");

        JsonNode entry = findEntry(superToken, address);
        assertThat(entry.isMissingNode()).as("%s in the blocklist", address).isFalse();

        api.delete("/api/super/security/blocked-ips/" + entry.path("id").asText()).bearer(superToken).send()
                .expectStatus(204);
        api.get("/api/public/categories").header("X-Forwarded-For", address).send().expectStatus(200);
        assertThat(findEntry(superToken, address).isMissingNode()).isTrue();
    }

    @Test
    void onlyASingleAddressCanBeBlocked() {
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        block(superToken, "SMOKE-bad-ip").expectError(400, "INVALID_IP");
        block(superToken, "198.51.100.0/24").expectError(400, "INVALID_IP");
        block(superToken, "300.1.1.1").expectError(400, "INVALID_IP");
        block(superToken, "example.com").expectError(400, "INVALID_IP");
    }

    /** Stored as typed, "2001:db8::7" never matched the canonical form Tomcat reports. */
    @Test
    void anIpv6AddressIsStoredAndMatchedInCanonicalForm() {
        String superToken = login(newUser("super", "SUPER_ADMIN").email());
        String address = "2001:db8::" + Integer.toHexString(0x100 + (int) (Math.random() * 0xe00));

        block(superToken, address).expectStatus(204);
        JsonNode entry = findEntry(superToken, canonical(address));
        assertThat(entry.isMissingNode()).isFalse();
        api.get("/api/public/categories").header("X-Forwarded-For", address).send()
                .expectError(403, "IP_BLOCKED");

        api.delete("/api/super/security/blocked-ips/" + entry.path("id").asText()).bearer(superToken).send()
                .expectStatus(204);
    }

    @Test
    void theCallersOwnAddressAndLoopbackCannotBeBlocked() {
        String superToken = login(newUser("super", "SUPER_ADMIN").email());

        api.post("/api/super/security/block-ip").bearer(superToken).header("X-Forwarded-For", "203.0.113.250")
                .json(Json.object("ipAddress", "203.0.113.250", "reason", "self")).send()
                .expectError(400, "CANNOT_BLOCK_SELF");
        // Seen from elsewhere, so that it is the loopback rule that answers and not the self rule.
        api.post("/api/super/security/block-ip").bearer(superToken).header("X-Forwarded-For", "203.0.113.251")
                .json(Json.object("ipAddress", "127.0.0.1", "reason", "loopback")).send()
                .expectError(400, "CANNOT_BLOCK_LOOPBACK");
    }

    @Test
    void unblockingAnUnknownEntryIsNotFound() {
        api.delete("/api/super/security/blocked-ips/" + UUID.randomUUID())
                .bearer(login(newUser("super", "SUPER_ADMIN").email())).send()
                .expectError(404, "BLOCKED_IP_NOT_FOUND");
    }

    /** The JDK's canonical text of an address literal; a literal is parsed, never looked up. */
    private static String canonical(String literal) {
        try {
            return java.net.InetAddress.getByName(literal).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException(literal, e);
        }
    }

    private ApiClient.Response block(String token, String address) {
        return api.post("/api/super/security/block-ip").bearer(token)
                .json(Json.object("ipAddress", address, "reason", "integration test")).send();
    }

    private JsonNode findEntry(String token, String address) {
        for (JsonNode entry : api.get("/api/super/security/blocked-ips").bearer(token).send()
                .expectStatus(200).data()) {
            if (address.equals(entry.path("ipAddress").asText())) return entry;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
