package io.github.gshahrza.streaming.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Talks to a real server over HTTP and checks not only the content but that the response is really streamed:
 * the first piece must arrive long before the response is complete.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StreamingEndpointsTest {

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    record TimedLines(HttpResponse<Stream<String>> response, List<String> lines, long firstLineMillis, long totalMillis) {
    }

    TimedLines readLines(HttpRequest request) throws IOException, InterruptedException {
        long start = System.nanoTime();
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        List<String> lines = new ArrayList<>();
        long[] first = {-1};
        response.body().forEach(line -> {
            if (first[0] < 0 && !line.isBlank()) {
                first[0] = (System.nanoTime() - start) / 1_000_000;
            }
            lines.add(line);
        });
        return new TimedLines(response, lines, first[0], (System.nanoTime() - start) / 1_000_000);
    }

    HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    }

    @Test
    void chatStreamsTokensAsServerSentEvents() throws Exception {
        TimedLines result = readLines(get("/api/chat/stream?prompt=hi"));

        assertThat(result.response().headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("text/event-stream"));
        assertThat(result.lines()).contains("event:token", "event:done");
        String text = result.lines().stream()
                .filter(l -> l.startsWith("data:{\"text\""))
                .map(l -> l.replaceAll("^data:\\{\"text\":\"(.*)\"}$", "$1"))
                .reduce("", String::concat);
        assertThat(text).startsWith("You asked: \\\"hi\\\". Streaming lets the server");
        assertThat(result.firstLineMillis()).isLessThan(result.totalMillis() / 2);
    }

    @Test
    void jobProgressStreamsAndResumesFromLastEventId() throws Exception {
        HttpResponse<String> started = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/jobs"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).isEqualTo(202);
        String events = started.headers().firstValue("Location").orElseThrow();

        TimedLines full = readLines(get(events));
        assertThat(full.lines().stream().filter(l -> l.startsWith("id:")).toList())
                .containsExactly(Stream.iterate(1, i -> i + 1).limit(20).map(i -> "id:" + i).toArray(String[]::new));
        assertThat(full.lines()).contains("event:completed");

        // Reconnect with the id the browser saw last: only the remaining events are sent
        TimedLines resumed = readLines(HttpRequest.newBuilder(URI.create("http://localhost:" + port + events))
                .header("Last-Event-ID", "17").build());
        assertThat(resumed.lines().stream().filter(l -> l.startsWith("id:")).toList())
                .containsExactly("id:18", "id:19", "id:20");
    }

    @Test
    void unknownJobIs404() throws Exception {
        HttpResponse<String> response = http.send(get("/api/jobs/nope/events"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void ndjsonDeliversFirstRowsBeforeTheLastPageIsRead() throws Exception {
        TimedLines result = readLines(get("/api/orders/stream?count=1000")); // 10 pages x 100ms

        assertThat(result.response().headers().firstValue("Content-Type")).hasValue("application/x-ndjson");
        assertThat(result.lines()).hasSize(1000);
        assertThat(result.lines().get(0)).startsWith("{\"id\":1,");
        assertThat(result.firstLineMillis()).isLessThan(result.totalMillis() - 500);
    }

    @Test
    void plainJsonArrivesOnlyAtTheEnd() throws Exception {
        TimedLines result = readLines(get("/api/orders?count=1000"));
        // A single line (the whole JSON array) that arrives after all 10 pages
        assertThat(result.lines()).hasSize(1);
        assertThat(result.firstLineMillis()).isGreaterThanOrEqualTo(900);
    }

    @Test
    void csvExportIsStreamedAsAttachment() throws Exception {
        TimedLines result = readLines(get("/api/orders/export.csv?count=1000"));

        assertThat(result.response().headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"orders.csv\"");
        assertThat(result.response().headers().firstValue("X-Accel-Buffering")).hasValue("no");
        assertThat(result.lines()).hasSize(1001).first().isEqualTo("id,customer,amount,status,createdAt");
        assertThat(result.firstLineMillis()).isLessThan(result.totalMillis() - 500);
    }

    @Test
    void invalidCountIsRejected() throws Exception {
        HttpResponse<String> response = http.send(get("/api/orders/stream?count=0"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }
}
