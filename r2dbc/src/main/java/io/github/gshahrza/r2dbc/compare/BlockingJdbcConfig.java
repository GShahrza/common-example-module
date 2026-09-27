package io.github.gshahrza.r2dbc.compare;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Only for the comparison. A real WebFlux + R2DBC application has no JDBC pool (apart from
 * the migration tool at startup).
 */
@Configuration
class BlockingJdbcConfig {

    @Bean
    @ConfigurationProperties("blocking.datasource")
    HikariDataSource blockingDataSource() {
        return new HikariDataSource();
    }

    @Bean
    JdbcClient blockingJdbcClient(HikariDataSource blockingDataSource) {
        return JdbcClient.create(blockingDataSource);
    }
}
