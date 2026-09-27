package io.github.gshahrza.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gshahrza.ai.rag.DocsIngestion;
import io.github.gshahrza.ai.tools.OrderStore;
import io.github.gshahrza.ai.tools.OrderTools;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(FakeModels.class)
class SpringAiTest {

    @LocalServerPort
    int port;

    @Autowired
    FakeModels.FakeChatModel model;

    @Autowired
    DocsIngestion ingestion;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        model.prompts.clear();
        model.answer = p -> "Hello from the fake model";
    }

    String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Test
    void chatIsStreamedTokenByTokenAsServerSentEvents() throws Exception {
        String body = get("/api/chat/stream?conversationId=c1&message=" + q("Salam"));

        assertThat(body).contains("event:token", "data:{\"text\":\"Hello\"}", "data:{\"text\":\" from\"}", "event:done");
    }

    @Test
    void chatMemorySendsEarlierMessagesOfTheSameConversation() throws Exception {
        get("/api/chat/stream?conversationId=c2&message=" + q("Mənim adım Aysel"));
        get("/api/chat/stream?conversationId=c2&message=" + q("Adım nədir?"));
        get("/api/chat/stream?conversationId=other&message=" + q("Adım nədir?"));

        var second = model.prompts.get(1).getInstructions();
        assertThat(second).extracting(Message::getText).contains("Mənim adım Aysel", "Hello from the fake model");
        var otherConversation = model.prompts.get(2).getInstructions();
        assertThat(otherConversation).extracting(Message::getText).doesNotContain("Mənim adım Aysel");
    }

    @Test
    void structuredOutputIsParsedIntoARecord() throws Exception {
        model.answer = p -> """
                {"customer":"Aysel","items":[{"product":"Laptop","quantity":2,"unitPrice":1200}],
                 "total":2400,"currency":"AZN","deliveryCity":"Bakı"}""";

        HttpResponse<String> response = post("/api/extract", "{\"text\":\"Aysel 2 laptop sifariş etdi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"customer\":\"Aysel\"", "\"quantity\":2", "\"currency\":\"AZN\"");
        // Spring AI told the model which JSON shape to produce
        String sentPrompt = model.prompts.getFirst().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER).findFirst().orElseThrow().getText();
        assertThat(sentPrompt).contains("deliveryCity", "unitPrice");
    }

    @Test
    void ragRetrievesTheMatchingChapterAndAnswersFromIt() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (ingestion.status().state() != DocsIngestion.State.READY && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(ingestion.status().chunks()).isGreaterThan(19);

        String body = get("/api/rag/stream?question=" + q("reconnectTime heartbeat əlfəcin Last-Event-ID nə üçündür?"));

        assertThat(body).startsWith("event:sources").contains("03-yeniden-qosulma.md", "event:token", "event:done");
        // The retrieved text was given to the model as context
        String system = model.prompts.getLast().getInstructions().getFirst().getText();
        assertThat(system).contains("Context:", "Last-Event-ID");
    }

    @Test
    void toolsReadAndChangeOrders() {
        OrderTools tools = new OrderTools(new OrderStore());

        assertThat(tools.getOrder(7)).contains("status DELIVERED");
        assertThat(tools.cancelOrder(7)).contains("cannot be cancelled");
        assertThat(tools.cancelOrder(4)).isEqualTo("Order 4 was cancelled");
        assertThat(tools.getOrder(9999)).contains("does not exist");
        assertThat(tools.calls()).hasSize(4).first().extracting(OrderTools.ToolCall::tool).isEqualTo("getOrder");
    }
}
