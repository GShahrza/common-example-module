package io.github.gshahrza.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketChatTest {

    @LocalServerPort
    int port;

    private final List<StompSession> sessions = new ArrayList<>();
    private static final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    static {
        scheduler.initialize();
    }

    @AfterEach
    void disconnect() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
    }

    StompSession connect(String name) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        client.setTaskScheduler(scheduler);   // needed for heartbeats
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws?name=" + name,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    /**
     * Subscribes and collects every received payload. The simple broker sends no RECEIPT frames,
     * so the subscription is confirmed with a request-reply round trip instead: the server keeps
     * the order of one client's frames, so when the reply arrives the SUBSCRIBE was processed.
     */
    <T> BlockingQueue<T> subscribe(StompSession session, String destination, Class<T> type) throws Exception {
        BlockingQueue<T> received = listen(session, destination, type);
        if (!destination.startsWith("/app/")) {
            next(listen(session, "/app/online", List.class));
        }
        return received;
    }

    <T> BlockingQueue<T> listen(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(type.cast(payload));
            }
        });
        return received;
    }

    static <T> T next(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("message received").isNotNull();
        return value;
    }

    @Test
    void roomMessageIsBroadcastToAllSubscribers() throws Exception {
        StompSession aynur = connect("Aynur");
        StompSession rashad = connect("Rashad");
        var aynurInbox = subscribe(aynur, "/topic/rooms/general", Map.class);
        var rashadInbox = subscribe(rashad, "/topic/rooms/general", Map.class);

        aynur.send("/app/rooms/general", Map.of("text", "Salam!"));

        for (var inbox : List.of(aynurInbox, rashadInbox)) {
            Map<?, ?> message = next(inbox);
            assertThat(message.get("from")).isEqualTo("Aynur");
            assertThat(message.get("text")).isEqualTo("Salam!");
            assertThat(message.get("room")).isEqualTo("general");
        }
    }

    @Test
    void lateJoinerGetsTheHistoryOnSubscribe() throws Exception {
        StompSession aynur = connect("Aynur");
        var inbox = subscribe(aynur, "/topic/rooms/history-test", Map.class);
        aynur.send("/app/rooms/history-test", Map.of("text", "birinci"));
        next(inbox);

        StompSession late = connect("Late");
        List<?> history = next(subscribe(late, "/app/rooms/history-test/history", List.class));

        assertThat(history).hasSize(1);
        assertThat(((Map<?, ?>) history.getFirst()).get("text")).isEqualTo("birinci");
    }

    @Test
    void privateMessageReachesOnlyTheRecipient() throws Exception {
        StompSession aynur = connect("Aynur");
        StompSession rashad = connect("Rashad");
        StompSession kamran = connect("Kamran");
        var rashadInbox = subscribe(rashad, "/user/queue/private", Map.class);
        var kamranInbox = subscribe(kamran, "/user/queue/private", Map.class);
        var aynurCopy = subscribe(aynur, "/user/queue/private", Map.class);

        aynur.send("/app/private/Rashad", Map.of("text", "gizli"));

        assertThat(next(rashadInbox).get("text")).isEqualTo("gizli");
        assertThat(next(aynurCopy).get("to")).isEqualTo("Rashad");
        assertThat(kamranInbox.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void presenceFollowsConnectAndDisconnect() throws Exception {
        StompSession aynur = connect("Aynur");
        var online = subscribe(aynur, "/topic/online", List.class);

        StompSession nigar = connect("Nigar");
        await().atMost(Duration.ofSeconds(5)).until(() -> online.stream().anyMatch(l -> l.contains("Nigar")));

        online.clear();
        nigar.disconnect();
        await().atMost(Duration.ofSeconds(5)).until(() -> online.stream().anyMatch(l -> !l.contains("Nigar")));
    }

    @Test
    void invalidMessageReturnsAnErrorOnlyToTheSender() throws Exception {
        StompSession aynur = connect("Aynur");
        var errors = subscribe(aynur, "/user/queue/errors", Map.class);

        aynur.send("/app/rooms/general", Map.of("text", "   "));

        assertThat(next(errors).get("message")).asString().contains("1..1000");
    }

    @Test
    void handshakeWithoutNameIsRejected() {
        assertThatThrownBy(() -> connect("")).hasStackTraceContaining("401");
        assertThat(sessions).isEmpty();
    }

    @Test
    void rawWebSocketEchoesTextFrames() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                received.add(message.getPayload());
            }
        }, "ws://localhost:" + port + "/ws/echo").get(5, TimeUnit.SECONDS);

        assertThat(next(received)).startsWith("connected: ");
        session.sendMessage(new TextMessage("salam"));
        assertThat(next(received)).isEqualTo("echo: salam");
        session.close();
    }
}
