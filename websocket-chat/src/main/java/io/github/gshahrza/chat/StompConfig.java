package io.github.gshahrza.chat;

import java.security.Principal;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * STOMP over WebSocket.
 *   /ws            the WebSocket endpoint (one connection per browser tab)
 *   /app/...       messages from clients go to @MessageMapping methods
 *   /topic/...     broadcast destinations (everyone subscribed receives)
 *   /user/queue/.. per-user destinations (only that user's sessions receive)
 */
@Configuration
@EnableWebSocketMessageBroker
class StompConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new RequireNameInterceptor())
                .setHandshakeHandler(new NameHandshakeHandler());
        // Messages of one client are handled in the order they were sent (default: in parallel)
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker. With several instances use enableStompBrokerRelay(...) (RabbitMQ, ActiveMQ)
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    static String name(ServerHttpRequest request) {
        String name = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("name");
        return name == null ? "" : name.strip();
    }

    /** Runs before the upgrade to WebSocket: a plain HTTP 401 without a name. */
    static class RequireNameInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Map<String, Object> attributes) {
            if (name(request).isEmpty()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Exception exception) {
        }
    }

    /**
     * Demo authentication: the user name comes from ?name=... in the handshake URL. In a real
     * application Spring Security sets the Principal from the session or a token instead.
     */
    static class NameHandshakeHandler extends DefaultHandshakeHandler {

        @Override
        protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler, Map<String, Object> attributes) {
            String name = name(request);
            String user = name.length() > 20 ? name.substring(0, 20) : name;
            return () -> user;
        }
    }
}
