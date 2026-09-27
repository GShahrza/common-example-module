package io.github.gshahrza.events;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class KafkaEventsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaEventsApplication.class, args);
    }
}
