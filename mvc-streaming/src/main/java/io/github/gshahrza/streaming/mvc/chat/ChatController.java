package io.github.gshahrza.streaming.mvc.chat;

import io.github.gshahrza.streaming.mvc.config.Sleeper;
import io.github.gshahrza.streaming.mvc.config.StreamingProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Example 1: Server-Sent Events with {@link SseEmitter}.
 *
 * <p>The controller method returns immediately; the servlet thread is released and the response stays open.
 * Another thread pushes events into the emitter until it calls {@code complete()}.
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AnswerGenerator generator;
    private final ExecutorService executor;
    private final StreamingProperties properties;

    public ChatController(AnswerGenerator generator, ExecutorService streamingExecutor,
                          StreamingProperties properties) {
        this.generator = generator;
        this.executor = streamingExecutor;
        this.properties = properties;
    }

    @GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());

        // The client may close the tab at any time: stop producing as soon as the emitter is finished
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));

        executor.execute(() -> {
            try {
                int index = 0;
                for (String token : generator.tokens(prompt)) {
                    if (!open.get()) {
                        return;
                    }
                    // Send JSON, not raw text: SSE strips a leading space after "data:", which would glue words together
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(index++))
                            .name("token")
                            .data(Map.of("text", token), MediaType.APPLICATION_JSON));
                    if (!Sleeper.sleep(properties.chatTokenDelay())) {
                        return;
                    }
                }
                emitter.send(SseEmitter.event().name("done").data(Map.of("tokens", index), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                // Broken pipe: the browser went away. Nothing to send it anymore.
                log.debug("Chat client disconnected: {}", e.getMessage());
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
