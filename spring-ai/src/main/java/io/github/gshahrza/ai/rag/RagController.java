package io.github.gshahrza.ai.rag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 4. RAG (retrieval-augmented generation): answer questions about the streaming book in docs/.
 * The steps are written out on purpose; QuestionAnswerAdvisor does the same in one line.
 */
@RestController
public class RagController {

    private static final String SYSTEM = """
            Answer the question using only the context below, which comes from a book about
            streaming with Spring (Spring MVC, WebFlux, gRPC). If the context does not contain
            the answer, say that the book does not cover it. Answer in the language of the question.

            Context:
            %s
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DocsIngestion ingestion;

    public RagController(ChatClient.Builder builder, VectorStore vectorStore, DocsIngestion ingestion) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
        this.ingestion = ingestion;
    }

    @GetMapping("/api/rag/status")
    public DocsIngestion.Status status() {
        return ingestion.status();
    }

    @GetMapping(path = "/api/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> ask(@RequestParam String question) {
        if (ingestion.status().state() != DocsIngestion.State.READY) {
            return Flux.just(event("error", Map.of("message", "The book is not indexed yet: " + ingestion.status().state())));
        }
        // Step 2: retrieval. The question is embedded and the most similar chunks are found.
        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(4)
                .build());
        String context = found.stream().map(Document::getText).collect(Collectors.joining("\n\n---\n\n"));
        List<Map<String, Object>> sources = found.stream()
                .map(d -> Map.<String, Object>of(
                        "file", String.valueOf(d.getMetadata().get("file")),
                        "score", d.getScore() == null ? 0 : Math.round(d.getScore() * 100) / 100.0,
                        "preview", preview(d.getText())))
                .toList();

        // Step 3: generation. The model answers from the retrieved text, not from memory.
        Flux<ServerSentEvent<Object>> answer = chatClient.prompt()
                .system(SYSTEM.formatted(context))
                .user(question)
                .stream()
                .content()
                .map(token -> event("token", Map.of("text", token)));

        return Flux.just(event("sources", sources))
                .concatWith(answer)
                .concatWith(Flux.just(event("done", Map.of())))
                .onErrorResume(e -> Flux.just(event("error", Map.of("message", String.valueOf(e.getMessage())))));
    }

    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 140 ? oneLine.substring(0, 140) + "..." : oneLine;
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.<Object>builder().event(name).data(data).build();
    }
}
