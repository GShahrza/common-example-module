package io.github.gshahrza.streaming.webflux;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(Duration chatTokenDelay, Duration orderPageDelay, int orderPageSize) {
}
