package com.eduplatform.eduplatform_backend.common.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bound from {@code app.ratelimit.*}.
 *
 * <p>Each rule ships its own budget in {@link RateLimitRule}; entries under
 * {@code app.ratelimit.rules.<key>} override the capacity, the window, or just one of the two.
 * Both {@link Limit} components are therefore nullable — so
 * {@code app.ratelimit.rules.password-forgot.capacity=5} raises the allowance without silently
 * resetting the one-hour window to something else.
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50000") int maxTrackedClients,
        Map<String, Limit> rules
) {

    /** A partial override of one rule's budget; {@code null} components keep the shipped default. */
    public record Limit(Integer capacity, Duration window) {}

    /** The budget actually applied to a rule, after overrides are merged in. */
    public record Budget(int capacity, Duration window) {}

    public RateLimitProperties {
        // `rules` is bound null when the prefix is absent entirely, and a rule key listed with no
        // body binds to a null Limit; both mean "no override", not a later NPE.
        rules = rules == null ? Map.of() : rules.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        if (maxTrackedClients < 1) {
            throw new IllegalArgumentException("app.ratelimit.max-tracked-clients must be >= 1");
        }
    }

    /**
     * Effective budget for {@code rule}: the configured override where present, else the default.
     * Called once per rule at startup, so a bad value fails the boot rather than a request.
     */
    public Budget budgetFor(RateLimitRule rule) {
        Limit override = rules.get(rule.key());
        int capacity = (override != null && override.capacity() != null)
                ? override.capacity() : rule.defaultCapacity();
        Duration window = (override != null && override.window() != null)
                ? override.window() : rule.defaultWindow();
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "app.ratelimit.rules." + rule.key() + ".capacity must be >= 1");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "app.ratelimit.rules." + rule.key() + ".window must be a positive duration");
        }
        return new Budget(capacity, window);
    }
}
