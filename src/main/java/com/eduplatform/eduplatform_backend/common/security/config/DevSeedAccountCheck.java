package com.eduplatform.eduplatform_backend.common.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Warns, outside the dev profile, when the database holds live accounts from the dev seed
 * (db/dev: {@code *@eduplatform.local}, every one with the published password Password123!, one of
 * them a SUPER_ADMIN).
 *
 * <p>StartupSecurityValidator keeps db/dev out of the Flyway path and prod refuses a history that
 * ran V6/V7, but a database that was once started under the dev profile — or restored from one —
 * keeps the accounts whatever the configuration says now. The validator runs before the database
 * is reachable, so this looks once the application is up.
 *
 * <p>A warning, not a refusal to start: the fix is to disable or delete those accounts, which
 * needs the application running, and a production outage is not the way to report it.
 */
@Component
public class DevSeedAccountCheck {

    private static final Logger log = LoggerFactory.getLogger(DevSeedAccountCheck.class);

    private final Environment env;
    private final JdbcTemplate jdbc;

    public DevSeedAccountCheck(Environment env, JdbcTemplate jdbc) {
        this.env = env;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutSeedAccounts() {
        if (Arrays.asList(env.getActiveProfiles()).contains("dev")) return;
        try {
            Integer live = jdbc.queryForObject("""
                    select count(*) from users
                    where deleted_at is null and status = 'ACTIVE' and email like '%@eduplatform.local'
                    """, Integer.class);
            if (live != null && live > 0) {
                log.warn("SECURITY: {} active account(s) from the dev seed (*@eduplatform.local, password "
                        + "Password123!) exist in this database. Disable or delete them on the Users page.", live);
            }
        } catch (Exception ex) {
            log.warn("Could not check for dev seed accounts", ex);
        }
    }
}
