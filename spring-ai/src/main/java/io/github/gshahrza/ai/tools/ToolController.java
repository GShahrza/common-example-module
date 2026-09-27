package io.github.gshahrza.ai.tools;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 3. Tool calling: the model answers questions about orders by calling Java methods. */
@RestController
public class ToolController {

    public record Question(String question) {
    }

    public record Answer(String answer, List<OrderTools.ToolCall> toolCalls) {
    }

    private final ChatClient chatClient;
    private final OrderStore store;

    public ToolController(ChatClient.Builder builder, OrderStore store) {
        this.store = store;
        this.chatClient = builder
                .defaultSystem("""
                        You are an order support assistant. Use the tools to look up or cancel orders.
                        Never invent order data. Answer in the language of the user's question.
                        """)
                .build();
    }

    @PostMapping("/api/tools/ask")
    public Answer ask(@RequestBody Question question) {
        OrderTools tools = new OrderTools(store);
        String answer = chatClient.prompt()
                .user(question.question())
                .tools(tools)
                .call()
                .content();
        return new Answer(answer, tools.calls());
    }
}
