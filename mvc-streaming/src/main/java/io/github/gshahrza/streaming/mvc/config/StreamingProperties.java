package io.github.gshahrza.streaming.mvc.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Artificial delays that make the streaming visible in the browser. */
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
        Duration chatTokenDelay,
        Duration jobStepDelay,
        Duration orderPageDelay,
        int orderPageSize) {
}
