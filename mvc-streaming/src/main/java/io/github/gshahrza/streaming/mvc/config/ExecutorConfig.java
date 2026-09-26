package io.github.gshahrza.streaming.mvc.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ExecutorConfig {

    /**
     * Producers of SSE / NDJSON events run here. A virtual thread per stream is cheap even with
     * thousands of open connections, and a blocking sleep or DB call does not tie up a platform thread.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService streamingExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
