package io.github.gshahrza.streaming.grpc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GrpcStreamingApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrpcStreamingApplication.class, args);
    }
}
