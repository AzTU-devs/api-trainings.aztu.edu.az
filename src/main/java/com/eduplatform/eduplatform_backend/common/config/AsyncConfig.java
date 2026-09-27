package com.eduplatform.eduplatform_backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executors for {@code @Async} work.
 *
 * <p>Spring Boot's own {@code applicationTaskExecutor} is not created in this application — the
 * WebSocket broker's executors satisfy the condition it backs off on — so a bare {@code @Async}
 * fell back to SimpleAsyncTaskExecutor: one new, unbounded thread per call. The API access log
 * writes one row per /api request that way, and under load those threads piled up waiting for
 * the same connection pool the requests themselves need. Setting spring.task.execution.* has no
 * effect here, since that configures the executor that is not being created.
 */
@Configuration
public class AsyncConfig {

    /**
     * The API access log's writer: two threads and a bounded queue. The log is best-effort, so
     * when the queue is full a row is dropped rather than a thread or a connection taken away
     * from serving requests.
     */
    @Bean(name = "apiLogExecutor")
    public ThreadPoolTaskExecutor apiLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("api-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
