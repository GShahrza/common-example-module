package io.github.gshahrza.r2dbc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class R2dbcApplication {

    static {
        // Netty uses one event-loop thread per CPU core. Fixed to 4 here so that the comparison
        // endpoints give the same picture on a laptop, a CI runner and a 32-core server.
        System.setProperty("reactor.netty.ioWorkerCount", "4");
    }

    public static void main(String[] args) {
        SpringApplication.run(R2dbcApplication.class, args);
    }
}
