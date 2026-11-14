package com.example.employees.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    /** "Today" for date-range validation */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Single worker thread: uploads are processed one at a time, FIFO.
     * Serial processing makes cross-file dedup trivially correct.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService importExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "import-worker");
            t.setDaemon(true);
            return t;
        });
    }
}
