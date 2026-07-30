package cn.camera.safe.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ExecutorConfiguration {
    public static final String ROUTE_EXECUTOR = "routeCalculationExecutor";
    public static final String REFRESH_EXECUTOR = "snapshotRefreshExecutor";
    public static final String INITIALIZATION_EXECUTOR = "backendInitializationExecutor";

    @Bean(name = ROUTE_EXECUTOR, destroyMethod = "shutdownNow")
    ExecutorService routeCalculationExecutor(AppProperties properties) {
        int threads = properties.routing().calculationThreads();
        return new ThreadPoolExecutor(
                threads,
                threads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.routing().calculationQueueCapacity()),
                namedThreads("route-calc-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = REFRESH_EXECUTOR, destroyMethod = "shutdownNow")
    ExecutorService snapshotRefreshExecutor() {
        return Executors.newSingleThreadExecutor(namedThreads("snapshot-refresh-"));
    }

    @Bean(name = INITIALIZATION_EXECUTOR, destroyMethod = "shutdownNow")
    ExecutorService backendInitializationExecutor() {
        return Executors.newSingleThreadExecutor(namedThreads("backend-init-"));
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
