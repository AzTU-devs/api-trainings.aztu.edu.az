package com.eduplatform.eduplatform_backend.common.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * In-memory token buckets, one per (rule, caller) pair.
 *
 * <p>The storage is deliberately bounded. A plain map keyed on client IP grows with whatever an
 * attacker chooses to send, which would make the rate limiter its own memory-exhaustion vector, so
 * Caffeine caps the entry count and discards buckets idle for longer than they need to refill.
 * Eviction can only ever hand a caller a fresh (full) bucket, so the cap is set high enough that
 * exhausting it takes tens of thousands of distinct source addresses, and Caffeine's
 * frequency-aware policy retains the busiest keys — which are precisely the abusive ones.
 */
final class RateLimitBucketStore {

    private final Map<RateLimitRule, Bandwidth> bandwidths = new EnumMap<>(RateLimitRule.class);
    private final Cache<String, Bucket> buckets;

    RateLimitBucketStore(RateLimitProperties props) {
        Duration longestWindow = Duration.ZERO;
        for (RateLimitRule rule : RateLimitRule.values()) {
            RateLimitProperties.Budget budget = props.budgetFor(rule);
            // Greedy refill drips tokens back continuously. Interval refill would release the whole
            // allowance on a window boundary, letting a caller spend two full budgets back to back.
            bandwidths.put(rule, Bandwidth.builder()
                    .capacity(budget.capacity())
                    .refillGreedy(budget.capacity(), budget.window())
                    .build());
            if (budget.window().compareTo(longestWindow) > 0) longestWindow = budget.window();
        }
        // The idle TTL has to outlast the longest window: expiring a bucket sooner would restore the
        // allowance earlier than the budget itself does, i.e. quietly widen the limit.
        this.buckets = Caffeine.newBuilder()
                .maximumSize(props.maxTrackedClients())
                .expireAfterAccess(longestWindow.multipliedBy(2))
                .build();
    }

    /** The bucket for this rule and caller, created full on first use. */
    Bucket bucketFor(RateLimitRule rule, String clientKey) {
        return buckets.get(rule.key() + '|' + clientKey,
                key -> Bucket.builder().addLimit(bandwidths.get(rule)).build());
    }
}
