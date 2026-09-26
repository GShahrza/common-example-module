package io.github.gshahrza.streaming.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MvcStreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MvcStreamingApplication.class, args);
    }
}
