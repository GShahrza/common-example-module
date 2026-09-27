package io.github.gshahrza.ai.extract;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 2. Structured output: free text in, a typed Java record out. */
@RestController
public class ExtractController {

    public record Text(String text) {
    }

    private final ChatClient chatClient;

    public ExtractController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("""
                        Extract order data from the user's text. Use null for anything that is not mentioned.
                        Do not guess prices. Currency as an ISO code (AZN, USD, EUR).
                        """)
                .build();
    }

    @PostMapping("/api/extract")
    public OrderDraft extract(@RequestBody Text text) {
        return chatClient.prompt()
                .user(text.text())
                .call()
                .entity(OrderDraft.class);
    }
}
