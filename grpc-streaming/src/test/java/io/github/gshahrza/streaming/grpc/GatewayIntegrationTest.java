package io.github.gshahrza.streaming.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Whole application: browser-facing HTTP gateway -> gRPC client channel -> gRPC server on a real port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayIntegrationTest {

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void unaryCallAndGrpcStatusMappedToHttp() throws Exception {
        assertThat(get("/api/orders/7").body()).contains("\"id\":7", "\"customer\":\"Elvin\"", "\"status\":\"DELIVERED\"");

        HttpResponse<String> missing = get("/api/orders/9999999");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("\"grpcStatus\":\"NOT_FOUND\"");
    }

    @Test
    void serverStreamReachesTheBrowserAsNdjsonWhileRunning() throws Exception {
        long start = System.nanoTime();
        HttpResponse<java.util.stream.Stream<String>> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/stream?count=1000")).build(),
                HttpResponse.BodyHandlers.ofLines());
        List<String> lines = new ArrayList<>();
        long[] first = {-1};
        response.body().forEach(l -> {
            if (first[0] < 0) {
                first[0] = (System.nanoTime() - start) / 1_000_000;
            }
            lines.add(l);
        });
        long total = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/x-ndjson");
        assertThat(lines).hasSize(1000);
        assertThat(first[0]).isLessThan(total - 500);
    }

    @Test
    void bidirectionalChatForwardedAsServerSentEvents() throws Exception {
        String q = "?q=" + URLEncoder.encode("salam", StandardCharsets.UTF_8)
                + "&q=" + URLEncoder.encode("gRPC nədir", StandardCharsets.UTF_8);
        String body = get("/api/chat" + q).body();

        assertThat(body).contains("event:token", "\"question\":\"salam\"", "\"question\":\"gRPC nədir\"", "event:end");
        assertThat(body.split("event:done", -1)).hasSize(3); // one "done" per question
    }

    @Test
    void clientStreamingUploadAndProtobufSize() throws Exception {
        HttpResponse<String> upload = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/upload?count=5000"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(upload.body()).contains("\"received\":5000");

        assertThat(get("/api/size?count=1000").body()).contains("\"protobufBytes\":27899", "\"jsonBytes\":");
    }
}
