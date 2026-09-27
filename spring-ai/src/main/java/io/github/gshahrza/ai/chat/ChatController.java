package io.github.gshahrza.ai.chat;

import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 1. Streaming chat with memory. The model's answer is a Flux of tokens, forwarded to the
 * browser as Server-Sent Events: the same technique as mvc-streaming, now with a real LLM.
 */
@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public ChatController(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = builder
                .defaultSystem("""
                        You are a friendly assistant in a demo application about Spring and streaming.
                        Answer in the language of the user's question. Keep answers short.
                        """)
                // Stores the conversation and adds previous messages to every new prompt
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> stream(@RequestParam String message,
                                                             @RequestParam String conversationId) {
        Flux<ServerSentEvent<Map<String, String>>> tokens = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .map(token -> event("token", Map.of("text", token)));
        return tokens
                .concatWith(Flux.just(event("done", Map.of())))
                // An SSE response has already started (status 200), so errors travel as an event
                .onErrorResume(e -> Flux.just(event("error", Map.of("message", String.valueOf(e.getMessage())))));
    }

    /** Starts a new conversation: the model forgets everything said under this id. */
    @DeleteMapping("/api/chat/{conversationId}")
    public ResponseEntity<Void> forget(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
        return ResponseEntity.noContent().build();
    }

    private static ServerSentEvent<Map<String, String>> event(String name, Map<String, String> data) {
        return ServerSentEvent.<Map<String, String>>builder().event(name).data(data).build();
    }
}
