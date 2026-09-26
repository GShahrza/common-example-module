package io.github.gshahrza.streaming.webflux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WebfluxStreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebfluxStreamingApplication.class, args);
    }
}
