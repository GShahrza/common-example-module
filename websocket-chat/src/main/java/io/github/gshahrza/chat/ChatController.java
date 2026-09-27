package io.github.gshahrza.chat;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

@Controller
class ChatController {

    record Incoming(String text) {
    }

    record ChatMessage(String room, String from, String text, Instant at) {
    }

    record PrivateMessage(String from, String to, String text, Instant at) {
    }

    record Typing(String user, boolean typing) {
    }

    record Error(String message) {
    }

    private static final int HISTORY = 50;

    private final SimpMessagingTemplate messaging;
    private final Map<String, Deque<ChatMessage>> history = new ConcurrentHashMap<>();

    ChatController(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** Client sends to /app/rooms/{room}; everyone subscribed to /topic/rooms/{room} receives it. */
    @MessageMapping("/rooms/{room}")
    void send(@DestinationVariable String room, @Payload Incoming incoming, Principal user) {
        String text = incoming.text() == null ? "" : incoming.text().strip();
        if (text.isEmpty() || text.length() > 1000) {
            throw new IllegalArgumentException("A message must be 1..1000 characters");
        }
        ChatMessage message = new ChatMessage(room, user.getName(), text, Instant.now());
        Deque<ChatMessage> messages = history.computeIfAbsent(room, r -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
            if (messages.size() > HISTORY) {
                messages.removeFirst();
            }
        }
        messaging.convertAndSend("/topic/rooms/" + room, message);
    }

    /**
     * Request-reply over the socket: subscribing to /app/rooms/{room}/history returns the result
     * directly to this subscriber only, it does not go through the broker.
     */
    @SubscribeMapping("/rooms/{room}/history")
    List<ChatMessage> history(@DestinationVariable String room) {
        Deque<ChatMessage> messages = history.getOrDefault(room, new ArrayDeque<>());
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /** Ephemeral event: not stored, only forwarded. */
    @MessageMapping("/rooms/{room}/typing")
    void typing(@DestinationVariable String room, boolean typing, Principal user) {
        messaging.convertAndSend("/topic/rooms/" + room + "/typing", new Typing(user.getName(), typing));
    }

    /**
     * Private message: convertAndSendToUser("Aynur", "/queue/private") reaches every session
     * (tab, device) of user Aynur, and nobody else. The sender gets a copy for their own UI.
     */
    @MessageMapping("/private/{to}")
    void privateMessage(@DestinationVariable String to, @Payload Incoming incoming, Principal user) {
        PrivateMessage message = new PrivateMessage(user.getName(), to, incoming.text(), Instant.now());
        messaging.convertAndSendToUser(to, "/queue/private", message);
        messaging.convertAndSendToUser(user.getName(), "/queue/private", message);
    }

    /** Errors of a @MessageMapping go back only to the sender, on /user/queue/errors. */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    Error error(IllegalArgumentException e) {
        return new Error(e.getMessage());
    }
}
