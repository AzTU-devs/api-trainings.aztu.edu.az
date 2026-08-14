package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.web.dto.SystemHealthDto;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** Runtime/system health snapshot for the super-admin monitoring view. */
@Service
public class SystemService {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * 1024L * 1024L;

    private final Environment env;
    private final DataSource dataSource;

    public SystemService(Environment env, DataSource dataSource) {
        this.env = env;
        this.dataSource = dataSource;
    }

    public SystemHealthDto health() {
        Runtime rt = Runtime.getRuntime();
        long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        long memTotalMb = rt.maxMemory() / MB;
        long memUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB;

        File root = new File(".");
        long diskTotalGb = root.getTotalSpace() / GB;
        long diskUsedGb = (root.getTotalSpace() - root.getUsableSpace()) / GB;

        String[] profiles = env.getActiveProfiles();
        String environment = profiles.length == 0 ? "default" : String.join(",", profiles);
        String appVersion = versionOrDefault();

        List<SystemHealthDto.ServiceStatus> services = new ArrayList<>();
        services.add(databaseStatus());
        services.add(new SystemHealthDto.ServiceStatus("application", "UP", null, null));

        return new SystemHealthDto(
                uptimeSec,
                round1(cpuLoadPct()),
                memUsedMb, memTotalMb,
                diskUsedGb, diskTotalGb,
                appVersion, environment, null,
                services, List.of());
    }

    private SystemHealthDto.ServiceStatus databaseStatus() {
        long start = System.nanoTime();
        try (Connection c = dataSource.getConnection()) {
            boolean valid = c.isValid(2);
            long latency = (System.nanoTime() - start) / 1_000_000;
            return new SystemHealthDto.ServiceStatus("database", valid ? "UP" : "DEGRADED", latency, null);
        } catch (Exception ex) {
            return new SystemHealthDto.ServiceStatus("database", "DOWN", null, ex.getMessage());
        }
    }

    /** Process CPU load as a percentage, or 0 when the JVM doesn't expose it. */
    private static double cpuLoadPct() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                double load = sun.getProcessCpuLoad();
                return load < 0 ? 0.0 : load * 100.0;
            }
        } catch (Throwable ignored) {
            // com.sun.* not available on this JVM — fall through.
        }
        return 0.0;
    }

    private String versionOrDefault() {
        String v = getClass().getPackage().getImplementationVersion();
        if (v != null && !v.isBlank()) return v;
        String prop = env.getProperty("app.version");
        return prop != null ? prop : "dev";
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
