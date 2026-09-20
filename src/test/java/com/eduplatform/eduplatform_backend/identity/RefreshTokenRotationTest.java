package com.eduplatform.eduplatform_backend.identity;

import com.eduplatform.eduplatform_backend.common.security.TokenHasher;
import com.eduplatform.eduplatform_backend.support.AbstractIntegrationTest;
import com.eduplatform.eduplatform_backend.support.ApiClient;
import com.eduplatform.eduplatform_backend.support.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refresh-token rotation with a grace window. A token that a concurrent request rotated within the
 * last 30 seconds is the benign multi-tab race (two tabs refreshing on the same expiry, a browser
 * restoring a session, the public site prefetching several links at once), and is answered with the
 * very same child that rotation issued — however many times it is replayed. The same token
 * presented after the window is reuse: the whole family is revoked and the caller gets
 * REFRESH_TOKEN_REUSED.
 *
 * <p>Idempotent rather than "one extra sibling": minting a token per replay would let anyone holding
 * a just-rotated token collect unlimited sessions, and capping it needed a row lock that turned a
 * burst of replays into pool exhaustion. Repeating the existing answer needs no lock and no write.
 *
 * <p>Time is advanced by moving the family's stored timestamps into the past, not by sleeping:
 * every issue and revocation instant in the family shifts by the same amount, which is what the
 * passage of time looks like to any implementation of the window, whether it compares against the
 * token's revocation or its successor's issue.
 */
class RefreshTokenRotationTest extends AbstractIntegrationTest {

    @Test
    void aTokenPresentedAgainWithinTheGraceWindowGetsTheSameChildBack() {
        String original = refreshTokenOf(loginResponse(newUser("tabs", "USER").email(), PASSWORD).expectStatus(200));
        UUID family = familyOf(original);

        String firstTab = refreshTokenOf(refresh(original).expectStatus(200));
        // The second tab presents the same token five seconds after the first tab rotated it.
        advanceTime(family, 5);
        ApiClient.Response secondTabResponse = refresh(original).expectStatus(200);
        String secondTab = refreshTokenOf(secondTabResponse);

        assertThat(secondTabResponse.data().path("accessToken").asText()).isNotBlank();
        assertThat(secondTab).as("the replay is answered with the child the rotation issued").isEqualTo(firstTab);
        assertThat(familyOf(secondTab)).isEqualTo(family);
        assertThat(childrenOf(original)).as("tokens issued from the rotated token").isEqualTo(1);
        // Neither tab has been signed out, and the shared child still rotates normally.
        refresh(secondTab).expectStatus(200);
    }

    @Test
    void aTokenPresentedAgainAfterTheGraceWindowRevokesTheWholeFamily() {
        String original = refreshTokenOf(loginResponse(newUser("reuse", "USER").email(), PASSWORD).expectStatus(200));
        UUID family = familyOf(original);
        String successor = refreshTokenOf(refresh(original).expectStatus(200));

        advanceTime(family, 60);
        refresh(original).expectError(401, "REFRESH_TOKEN_REUSED");

        assertThat(liveTokensIn(family)).as("live tokens left in the family").isZero();
        // The successor was revoked by reuse detection a moment ago. A revocation that recent must
        // not pass for the benign race, or the thief's next call would revive the family.
        refresh(successor).expectStatus(401);
        assertThat(liveTokensIn(family)).as("live tokens left in the family").isZero();
    }

    /**
     * A replay loop gains nothing. Each grace answer used to mint another live 30-day sibling, so
     * anyone holding a token rotated in the last 30 seconds could collect as many sessions as the
     * rate limit allowed, none of which reuse detection would ever notice.
     */
    @Test
    void repeatedReplaysInsideTheWindowKeepGettingTheSameChildAndMintNothing() {
        String original = refreshTokenOf(loginResponse(newUser("replay", "USER").email(), PASSWORD).expectStatus(200));
        UUID family = familyOf(original);
        String child = refreshTokenOf(refresh(original).expectStatus(200));

        advanceTime(family, 5);
        assertThat(refreshTokenOf(refresh(original).expectStatus(200))).isEqualTo(child);
        advanceTime(family, 5);
        assertThat(refreshTokenOf(refresh(original).expectStatus(200))).isEqualTo(child);

        assertThat(childrenOf(original)).as("tokens issued from the rotated token").isEqualTo(1);
        assertThat(liveTokensIn(family)).as("live tokens left in the family").isEqualTo(1);
    }

    /**
     * The same answer when the presentations arrive together, which is the case the window exists
     * for: several tabs, or the public site prefetching links, all refreshing on one expiry.
     *
     * <p>Eight at once against a test pool of five, deliberately. Nothing here locks a row or opens
     * a second transaction, so each request needs one connection briefly and the burst simply
     * queues. The previous design would have deadlocked this.
     */
    @Test
    void simultaneousPresentationsOfARotatedTokenAllGetTheSameChild() throws Exception {
        String original = refreshTokenOf(loginResponse(newUser("burst", "USER").email(), PASSWORD).expectStatus(200));
        UUID family = familyOf(original);
        String child = refreshTokenOf(refresh(original).expectStatus(200));

        List<ApiClient.Response> responses = concurrently(8, () -> refresh(original));

        assertThat(responses).as("answers to the simultaneous presentations")
                .allSatisfy(response -> assertThat(response.status()).isEqualTo(200));
        assertThat(responses.stream().map(RefreshTokenRotationTest::refreshTokenOf).distinct())
                .as("every simultaneous replay is answered with the same child")
                .containsExactly(child);
        assertThat(childrenOf(original)).as("tokens issued from the rotated token").isEqualTo(1);
        assertThat(liveTokensIn(family)).as("live tokens left in the family").isEqualTo(1);
    }

    /**
     * Reuse detection revokes the family in the request's own transaction and keeps it through the
     * 401 via noRollbackFor. It used to do that in a nested transaction, which needed a second
     * pooled connection while the request still held its first — so a burst of replays this size
     * exhausted the pool, and every unrelated request on the server got a 500 until it drained.
     * Here the pool is five and the burst is eight: each answer must be its own clean 401.
     */
    @Test
    void simultaneousPresentationsAfterTheWindowAllFailWithoutExhaustingThePool() throws Exception {
        String original = refreshTokenOf(loginResponse(newUser("starve", "USER").email(), PASSWORD).expectStatus(200));
        UUID family = familyOf(original);
        refresh(original).expectStatus(200);
        advanceTime(family, 60);

        List<ApiClient.Response> responses = concurrently(8, () -> refresh(original));

        responses.forEach(response -> response.expectError(401, "REFRESH_TOKEN_REUSED"));
        assertThat(liveTokensIn(family)).as("live tokens left in the family").isZero();
    }

    private ApiClient.Response refresh(String refreshToken) {
        return api.post("/api/auth/refresh").json(Json.object("refreshToken", refreshToken)).send();
    }

    private static String refreshTokenOf(ApiClient.Response tokens) {
        String token = tokens.data().path("refreshToken").asText();
        assertThat(token).as("refresh token in %s", tokens.request()).isNotBlank();
        return token;
    }

    private UUID familyOf(String rawToken) {
        return jdbc.queryForObject("select family_id from refresh_tokens where token_hash = ?",
                UUID.class, TokenHasher.sha256Hex(rawToken));
    }

    private int liveTokensIn(UUID family) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ? and revoked_at is null",
                Integer.class, family);
    }

    /** Tokens issued by rotating {@code rawToken}, whether by ordinary rotation or by grace. */
    private int childrenOf(String rawToken) {
        return jdbc.queryForObject("""
                select count(*) from refresh_tokens
                 where parent_id = (select id from refresh_tokens where token_hash = ?)
                """, Integer.class, TokenHasher.sha256Hex(rawToken));
    }

    /** Runs {@code calls} copies of {@code call} released at the same instant, answers in submission order. */
    private static List<ApiClient.Response> concurrently(int calls, Callable<ApiClient.Response> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiClient.Response>> pending = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ApiClient.Response> responses = new ArrayList<>();
            for (Future<ApiClient.Response> response : pending) {
                responses.add(response.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Makes {@code seconds} pass for this family; expiry is left alone, so no token ages out. */
    private void advanceTime(UUID family, int seconds) {
        jdbc.update("""
                update refresh_tokens
                   set issued_at = issued_at - (? * interval '1 second'),
                       revoked_at = revoked_at - (? * interval '1 second')
                 where family_id = ?
                """, seconds, seconds, family);
    }
}
