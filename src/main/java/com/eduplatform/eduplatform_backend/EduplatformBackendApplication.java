package com.eduplatform.eduplatform_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableCaching
@EnableAsync
@EnableScheduling
public class EduplatformBackendApplication {

    public static void main(String[] args) {
        // hibernate.jdbc.time_zone=UTC converts LocalTime columns (class start/end times)
        // through the JVM's own zone, so on a JVM that is not in UTC 10:00 is stored as
        // 06:00. The container already runs in UTC; this keeps a JVM started anywhere
        // else (bootRun on a laptop in Baku, a host with TZ set) writing the same values.
        // Local dates and times are computed with app.timezone, never the JVM's zone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(EduplatformBackendApplication.class, args);
    }
}
