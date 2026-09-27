package io.github.gshahrza.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Default limit: 5 calls per second to the partner. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=18090")
class RateLimiterTest {

    final RestClient http = RestClient.create("http://localhost:18090");

    @Test
    @SuppressWarnings("unchecked")
    void burstAboveTheLimitIsRejectedWithoutCallingThePartner() throws Exception {
        http.get().uri("/api/rates").retrieve().toBodilessEntity();   // a "last good" value for the fallback
        Thread.sleep(1100);                                              // start with a fresh period

        List<Map<String, Object>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Map<String, Object>>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                calls.add(executor.submit(() -> (Map<String, Object>) http.get().uri("/api/rates").retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("rates")));
            }
            for (var call : calls) {
                results.add(call.get());
            }
        }

        long live = results.stream().filter(r -> "partner".equals(r.get("source"))).count();
        assertThat(live).isBetween(5L, 10L);   // 5 per period (10 if the burst crosses a period boundary)
        assertThat(results).filteredOn(r -> Boolean.TRUE.equals(r.get("stale")))
                .isNotEmpty()
                .allSatisfy(r -> assertThat((String) r.get("reason")).startsWith("RequestNotPermitted"));
    }
}
