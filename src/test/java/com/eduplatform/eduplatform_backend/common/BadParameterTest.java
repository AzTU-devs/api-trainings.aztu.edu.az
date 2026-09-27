package com.eduplatform.eduplatform_backend.common;

import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Malformed query parameters are the caller's mistake and get a 400. Each of these was a 500
 * (and an ERROR stack trace any anonymous caller could produce at will) or a misleading 409.
 */
class BadParameterTest extends AbstractIntegrationTest {

    /** page × size overflowed the int Spring Data computes the offset in. */
    @Test
    void anAbsurdPageNumberIsA400() {
        api.get("/api/public/courses").query("page", 999999999).send().expectError(400, "INVALID_PARAMETER");
        api.get("/api/admin/users").bearer(newAdminToken()).query("page", 214748365).send()
                .expectError(400, "INVALID_PARAMETER");
    }

    /** On endpoints backed by a @Query the sort reaches Hibernate's parser, not Spring's property check. */
    @Test
    void anUnknownOrMalformedSortOnAQueryBackedEndpointIsA400() {
        String adminToken = newAdminToken();
        api.get("/api/admin/users").bearer(adminToken).query("sort", "nonexistentField,asc").send()
                .expectError(400, "INVALID_SORT_PROPERTY");
        api.get("/api/admin/users").bearer(adminToken).query("sort", "id;DROP TABLE users").send()
                .expectError(400, "INVALID_SORT_PROPERTY");
        api.get("/api/portal/room-bookings/admin").bearer(adminToken).query("sort", "nope,desc").send()
                .expectError(400, "INVALID_SORT_PROPERTY");
    }

    /** A NUL byte is a data exception in Postgres (SQLSTATE 22), which used to read as a 409 conflict. */
    @Test
    void aNulByteInASearchIsA400() {
        api.get("/api/admin/users").bearer(newAdminToken()).query("search", "a\u0000b").send()
                .expectError(400, "INVALID_INPUT");
    }
}
