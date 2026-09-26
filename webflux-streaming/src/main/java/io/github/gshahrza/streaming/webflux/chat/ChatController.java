package io.github.gshahrza.streaming.webflux.chat;

import io.github.gshahrza.streaming.webflux.StreamingProperties;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * The same SSE chat as in mvc-streaming, reactive. Compare with the MVC version: no executor,
 * no emitter, no "is the client still there?" flag. The stream is just a Flux; when the browser
 * disconnects, WebFlux cancels the subscription and the remaining tokens are never produced.
 */
@RestController
public class ChatController {

    private final StreamingProperties properties;

    public ChatController(StreamingProperties properties) {
        this.properties = properties;
    }

    @GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream(@RequestParam String prompt) {
        AtomicInteger index = new AtomicInteger();
        Flux<ServerSentEvent<Map<String, Object>>> tokens = Flux.fromIterable(tokens(prompt))
                .delayElements(properties.chatTokenDelay())
                .map(token -> ServerSentEvent.<Map<String, Object>>builder()
                        .id(String.valueOf(index.getAndIncrement()))
                        .event("token")
                        .data(Map.of("text", token))
                        .build());
        Flux<ServerSentEvent<Map<String, Object>>> done = Flux.defer(() -> Flux.just(
                ServerSentEvent.<Map<String, Object>>builder().event("done").data(Map.of("tokens", index.get())).build()));
        return tokens.concatWith(done);
    }

    private static java.util.List<String> tokens(String prompt) {
        String answer = "You asked: \"" + prompt + "\". This answer is a Flux: every word is an element that WebFlux "
                + "writes to the response as soon as it is emitted, and no thread waits in between.";
        String[] words = answer.split(" ");
        return Arrays.stream(words).map(w -> w == words[0] ? w : " " + w).toList();
    }
}
