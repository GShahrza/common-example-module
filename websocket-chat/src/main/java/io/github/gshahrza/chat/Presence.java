package io.github.gshahrza.chat;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Who is online. A user can have several sessions (tabs); they are online while at least one
 * is connected. Disconnect is also fired when the browser is closed or the network drops,
 * because the server notices the closed TCP connection (or missing heartbeats).
 */
@Controller
class Presence {

    private final SimpMessagingTemplate messaging;
    private final Map<String, AtomicInteger> sessions = new ConcurrentHashMap<>();

    Presence(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @EventListener
    void connected(SessionConnectedEvent event) {
        Principal user = event.getUser();
        if (user != null) {
            sessions.computeIfAbsent(user.getName(), u -> new AtomicInteger()).incrementAndGet();
            broadcast();
        }
    }

    @EventListener
    void disconnected(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (user != null) {
            sessions.computeIfPresent(user.getName(), (u, count) -> count.decrementAndGet() <= 0 ? null : count);
            broadcast();
        }
    }

    @SubscribeMapping("/online")
    Set<String> online() {
        return new TreeSet<>(sessions.keySet());
    }

    private void broadcast() {
        messaging.convertAndSend("/topic/online", online());
    }
}
