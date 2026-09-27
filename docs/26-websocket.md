# 26. WebSocket və STOMP: real-time chat

[← 25. Spring AI](25-spring-ai.md) · [Mündəricat](README.md)

**Hissə IV: Əlavə mövzular** · **Kod:** [`StompConfig`](../websocket-chat/src/main/java/io/github/gshahrza/chat/StompConfig.java), [`ChatController`](../websocket-chat/src/main/java/io/github/gshahrza/chat/ChatController.java), [`Presence`](../websocket-chat/src/main/java/io/github/gshahrza/chat/Presence.java), [`RawWebSocketConfig`](../websocket-chat/src/main/java/io/github/gshahrza/chat/RawWebSocketConfig.java), [`index.html`](../websocket-chat/src/main/resources/static/index.html), [`WebSocketChatTest`](../websocket-chat/src/test/java/io/github/gshahrza/chat/WebSocketChatTest.java) · **Demo:** http://localhost:8086

```bash
./gradlew :websocket-chat:bootRun
```

Heç bir xarici servis lazım deyil. Səhifəni **iki tab-da** açın, fərqli adlarla qoşulun və yazışın.

---

## Həyatdan analogiya

İki nəfər bir-biri ilə üç yolla danışa bilər:

- **Məktub (HTTP).** Siz məktub göndərirsiniz, cavab gəlir. Qarşı tərəf sizə **özü** yaza bilməz, yalnız sizin məktubunuza cavab verə bilər. Yeni xəbər olub-olmadığını bilmək üçün hər dəqiqə "yeni nə var?" məktubu yazmalısınız. Bu, **polling**-dir.
- **Radio (SSE).** Stansiya danışır, siz dinləyirsiniz. Xəbər dərhal çatır, amma siz radioya cavab verə bilmirsiniz. Bunun üçün ayrıca məktub (HTTP sorğusu) yazırsınız.
- **Telefon zəngi (WebSocket).** Bir dəfə zəng edirsiniz, xətt açıq qalır, və **hər iki tərəf istədiyi an danışır**. Hər cümlə üçün yenidən nömrə yığmaq lazım deyil.

Telefon xəttinin özü yalnız səs daşıyır. Konfrans zəngində isə qaydalar lazımdır: "indi kim danışır?", "bu sözü hamıya deyirəm, yoxsa yalnız Rashad-a?", "3 nömrəli otağa keçək". Bu qaydalar **STOMP**-dur: telefon xəttinin üstündə danışıq protokolu.

## Problem

Biznes deyir: "Saytda chat olsun: otaqlar, şəxsi mesajlar, kim onlayndır, kim yazır." HTTP ilə bunu qurmağa çalışaq:

- Brauzer hər saniyə `GET /messages?after=...` göndərir. 1000 istifadəçi = saniyədə 1000 sorğu, onların çoxu boş cavab qaytarır. Gecikmə isə polling intervalı qədərdir.
- **Long polling** gecikməni azaldır, amma hər mesajdan sonra yeni sorğu, header-lər, timeout-lar və mürəkkəb server kodu tələb edir.
- **SSE** serverdən klientə axını yaxşı həll edir (bax: [2. Server-Sent Events](02-sse.md)), amma chat-də klient də tez-tez yazır: "yazır..." siqnalı hər düymə basılışında gedir. Hər biri üçün ayrıca HTTP `POST` göndərmək olar, amma bu, iki kanalı idarə etmək deməkdir.

Lazım olan budur: **bir bağlantı, iki istiqamət, minimal overhead.**

## WebSocket necə işləyir

### Handshake: HTTP ilə başlayır

Bağlantı adi HTTP sorğusu ilə açılır, və server razılaşanda eyni TCP bağlantısı WebSocket-ə keçir:

```
→ GET /ws?name=Aynur HTTP/1.1
  Upgrade: websocket
  Connection: Upgrade
  Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
  Sec-WebSocket-Version: 13
  Origin: http://localhost:8086

← HTTP/1.1 101 Switching Protocols
  Upgrade: websocket
  Connection: Upgrade
  Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
```

`101`-dən sonra HTTP bitir. Bundan sonra bağlantıda yalnız **frame**-lər gedir: `text`, `binary`, `ping`, `pong` və `close`. Frame başlığı cəmi 2-14 baytdır; hər HTTP sorğusundakı yüzlərlə bayt header yoxdur.

Handshake HTTP olduğu üçün autentifikasiya, cookie, `Origin` yoxlaması və imtina (`401`, `403`) məhz bu mərhələdə edilir. Upgrade baş verəndən sonra HTTP status kodu qaytarmaq artıq mümkün deyil.

Brauzerdə bunu görmək üçün DevTools-da **Network → WS** bölməsini açın: `/ws` sorğusunun statusu `101`-dir, **Messages** tabında isə hər frame görünür.

### Xam WebSocket: nə alırsınız, nə almırsınız

Modulda müqayisə üçün STOMP-suz, xam handler var:

```java
@Configuration
@EnableWebSocket
class RawWebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new EchoHandler(), "/ws/echo");
    }

    static class EchoHandler extends TextWebSocketHandler {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            session.sendMessage(new TextMessage("echo: " + message.getPayload()));
        }
    }
}
```

Demo səhifənin aşağısında "Xam WebSocket" bölməsi var: yazdığınız mətn `echo: ...` kimi geri qayıdır. İşləyir, amma chat üçün bu kifayət deyil. Bu mesaj hansı otağa aiddir? Kimə göndərilməlidir? Bu, adi mesajdır, "yazır..." siqnalıdır, yoxsa xəta? Xam WebSocket bu sualların heç birinə cavab vermir. Hər layihə öz JSON formatını icad edir (`{"type":"join","room":"..."}`), sonra da onun routing-ini, abunəliklərini və xəta idarəsini yazır.

## STOMP: telefon xəttinin üstündə qaydalar

STOMP HTTP-yə bənzəyən sadə mətn protokoludur: əmr, header-lər, boş sətir, gövdə və sonda `\0` (NUL) simvolu:

```
SEND
destination:/app/rooms/general
content-type:application/json

{"text":"Salam"}^@
```

Əsas əmrlər bunlardır:

| Klient → server | Server → klient |
|---|---|
| `CONNECT`: qoşulma, versiya, heartbeat | `CONNECTED`: qəbul edildi |
| `SUBSCRIBE` (`id`, `destination`) | `MESSAGE`: abunəliyə mesaj |
| `UNSUBSCRIBE` | `RECEIPT`: "aldım" təsdiqi |
| `SEND` (`destination`) | `ERROR` |
| `DISCONNECT` | |

Protokolun nə qədər sadə olduğunu göstərmək üçün demo səhifədəki klient STOMP-u **kitabxanasız**, bir neçə sətirlə həyata keçirir:

```javascript
// a minimal STOMP 1.2 client: frames are "COMMAND\nheader:value\n\nbody\0"
function frame(command, headers = {}, body = '') {
  const text = command + '\n' + Object.entries(headers).map(([k, v]) => k + ':' + v).join('\n')
             + '\n\n' + body + '\0';
  ws.send(text);
}
```

Səhifənin aşağısında göndərilən (→) və alınan (←) bütün frame-lər görünür. Real layihədə isə [`@stomp/stompjs`](https://github.com/stomp-js/stompjs) istifadə olunur: o, yenidən qoşulmanı və heartbeat-i də özü idarə edir.

## Həll, addım-addım

### 1. Konfiqurasiya: endpoint və broker

```java
@Configuration
@EnableWebSocketMessageBroker
class StompConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new RequireNameInterceptor())
                .setHandshakeHandler(new NameHandshakeHandler());
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
```

Destination prefiksləri mesajın hara gedəcəyini müəyyən edir:

```
                        /app/...                 /topic/..., /queue/...
brauzer ──SEND──►  @MessageMapping metodu  ──►        Simple broker        ──MESSAGE──► abunəçilər
         │                                                  ▲
         └──────────────SEND /topic/... (birbaşa)───────────┘
```

- `/app/...` controller-ə, yəni sizin koda gedir: yoxlama, bazaya yazmaq, zənginləşdirmək.
- `/topic/...` və `/queue/...` broker-ə gedir; broker mesajı həmin destination-a abunə olan hər sessiyaya paylayır.
- `/user/...` istifadəçiyə xüsusi destination-lardır (addım 4).

**Simple broker** Spring-in yaddaşda işləyən sadə broker-idir. Bir instance üçün kifayətdir; bir neçə instance üçün isə xarici broker lazımdır (bax: "Production").

### 2. Otaq: broadcast

```java
@MessageMapping("/rooms/{room}")
void send(@DestinationVariable String room, @Payload Incoming incoming, Principal user) {
    String text = incoming.text() == null ? "" : incoming.text().strip();
    if (text.isEmpty() || text.length() > 1000) {
        throw new IllegalArgumentException("A message must be 1..1000 characters");
    }
    ChatMessage message = new ChatMessage(room, user.getName(), text, Instant.now());
    // ... tarixçəyə yaz
    messaging.convertAndSend("/topic/rooms/" + room, message);
}
```

Spring MVC-dən tanış model: `@MessageMapping` `@RequestMapping`-ə, `@DestinationVariable` `@PathVariable`-a, `@Payload` isə `@RequestBody`-yə uyğundur. JSON Jackson ilə record-a çevrilir.

Diqqət edin: göndərənin adı mesajın gövdəsindən yox, **`Principal`-dan** götürülür. Klient `{"from":"Admin"}` yazsa belə, başqasının adından danışa bilməz.

Klient tərəfdə otağa qoşulmaq iki abunəlikdir:

```javascript
subscribe(`/app/rooms/${room}/history`, list => list.forEach(m => add(m)));  // bir dəfə: tarixçə
subscribe(`/topic/rooms/${room}`, m => add(m));                              // sonrakı hər mesaj
```

### 3. Tarixçə: `@SubscribeMapping` ilə request-reply

Otağa sonradan qoşulan istifadəçi əvvəlki mesajları görməlidir:

```java
@SubscribeMapping("/rooms/{room}/history")
List<ChatMessage> history(@DestinationVariable String room) {
    ...
    return List.copyOf(messages);
}
```

Klient `/app/rooms/general/history`-yə abunə olanda metodun nəticəsi **yalnız həmin klientə**, **bir dəfə** göndərilir və broker-dən keçmir. Bu, WebSocket üzərində request-reply-dır: "qoşulanda ilkin vəziyyəti al" üçün idealdır. Onlayn siyahısı da eyni üsulla alınır: `/app/online`.

Modulda tarixçə yaddaşdadır və hər otaq üçün son 50 mesajı saxlayır. Real layihədə isə bazada saxlanılır.

### 4. Şəxsi mesaj: `/user/...`

```java
@MessageMapping("/private/{to}")
void privateMessage(@DestinationVariable String to, @Payload Incoming incoming, Principal user) {
    PrivateMessage message = new PrivateMessage(user.getName(), to, incoming.text(), Instant.now());
    messaging.convertAndSendToUser(to, "/queue/private", message);
    messaging.convertAndSendToUser(user.getName(), "/queue/private", message);  // göndərənin öz ekranı üçün
}
```

Hər klient eyni ünvana abunə olur: `/user/queue/private`. Spring bunu hər sessiya üçün unikal ünvana çevirir (`/queue/private-user3xk2` kimi). `convertAndSendToUser("Rashad", ...)` isə Rashad-ın **bütün sessiyalarına** (bütün tab və cihazlarına) çatır. Başqa heç kim Rashad-ın unikal ünvanını bilmir və ona abunə ola bilmir.

Bunun işləməsi üçün hər sessiyanın `Principal`-ı olmalıdır. O, addım 7-də təyin olunur.

### 5. "Yazır...": saxlanmayan hadisə

```java
@MessageMapping("/rooms/{room}/typing")
void typing(@DestinationVariable String room, boolean typing, Principal user) {
    messaging.convertAndSend("/topic/rooms/" + room + "/typing", new Typing(user.getName(), typing));
}
```

Bu mesaj bazaya yazılmır və tarixçəyə düşmür, yalnız ötürülür. Klient hər düymə basılışında yox, yalnız vəziyyət dəyişəndə göndərir: yazmağa başlayanda `true`, 1.5 saniyə sükutdan sonra və ya mesaj göndəriləndə `false`. Hər düymə basılışında göndərmək otaqdakı hər kəsə saniyədə onlarla lazımsız mesaj deməkdir.

Bu hadisələr **iki növdür**, və onları ayırmaq vacibdir. **Vəziyyət** (mesaj) saxlanmalı və itməməlidir. **Siqnal** ("yazır...", kursorun yeri) isə itsə də olar, çünki sonrakı siqnal onu əvəz edir.

### 6. Kim onlayndır

```java
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
```

Bir istifadəçinin bir neçə tab-ı ola bilər, ona görə sessiyalar **sayılır**. İstifadəçi yalnız sonuncu tab bağlananda oflayn olur. `SessionDisconnectEvent` tab bağlananda, `DISCONNECT` frame-i gələndə, və şəbəkə kəsiləndə gəlir: server TCP bağlantısının bağlandığını görür. Şəbəkə səssizcə kəsilibsə (telefon tunelə girdi), bunu yalnız heartbeat aşkarlayır (bax: "Production").

### 7. Autentifikasiya: handshake-də

```java
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
}

static class NameHandshakeHandler extends DefaultHandshakeHandler {
    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler, Map<String, Object> attributes) {
        String name = name(request);
        return () -> name.length() > 20 ? name.substring(0, 20) : name;
    }
}
```

- `HandshakeInterceptor` upgrade-dən **əvvəl** işləyir: ad yoxdursa, adi HTTP `401` qaytarır və WebSocket açılmır.
- `HandshakeHandler.determineUser` sessiyanın `Principal`-ını təyin edir. Bütün `@MessageMapping` metodlarına gələn `Principal user` də, `/user/...` routing-i də budur.

Bu demo-da ad sadəcə URL-dən götürülür: bu, **autentifikasiya deyil**, çünki hər kəs istədiyi adı yaza bilər. Real layihədə `Principal`-ı Spring Security təyin edir:

- **Cookie və HTTP sessiyası:** handshake sorğusu adi HTTP sorğusudur, Spring Security onu yoxlayır və istifadəçi avtomatik sessiyaya bağlanır.
- **JWT:** brauzerin `WebSocket` API-si handshake-ə `Authorization` header-i əlavə etməyə imkan vermir. Ona görə token STOMP `CONNECT` frame-inin header-ində göndərilir və `ChannelInterceptor`-da yoxlanır:

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                String token = accessor.getFirstNativeHeader("Authorization");
                accessor.setUser(jwtAuthentication(token));   // etibarsızdırsa, exception atılır
            }
            return message;
        }
    });
}
```

JWT və refresh token haqqında: [23. Spring Security və JWT](23-security-jwt.md).

### 8. Xətanı yalnız göndərənə qaytarmaq

Boş mesaj göndəriləndə `send` metodu `IllegalArgumentException` atır:

```java
@MessageExceptionHandler
@SendToUser(destinations = "/queue/errors", broadcast = false)
Error error(IllegalArgumentException e) {
    return new Error(e.getMessage());
}
```

`@MessageExceptionHandler` `@ExceptionHandler`-in analoqudur. `@SendToUser` cavabı yalnız mesajı göndərən istifadəçiyə, `broadcast = false` isə yalnız **həmin sessiyaya** (tab-a) göndərir. Xəta otağa yayımlanmır: başqaları sizin xətanızı görməməlidir.

Xəta qaytarılanda JSON obyekt (record) kimi göndərilir, sadə `String` kimi yox. Bu modulu yazarkən `String` qaytarmaq mesaj converter-lərində gözlənilməz nəticə verdi; record ilə isə klient həmişə `{"message": "..."}` alır.

### 9. Sıra: `setPreserveReceiveOrder`

Default olaraq bir klientin frame-ləri serverdə thread pool-da **paralel** emal olunur. Klient `SUBSCRIBE /topic/rooms/general` və dərhal ardından `SEND /app/rooms/general` göndərsə, `SEND` daha tez işlənə bilər. Onda klient öz mesajını görmür.

```java
registry.setPreserveReceiveOrder(true);
```

Bu ayar bir sessiyanın frame-lərini göndərildiyi ardıcıllıqla emal edir. Müxtəlif sessiyalar isə əvvəlki kimi paralel işlənir. Chat üçün ardıcıllıq paralellikdən vacibdir.

### 10. Test: real server, real klient

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketChatTest {

    StompSession connect(String name) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        client.setTaskScheduler(scheduler);
        return client.connectAsync("ws://localhost:" + port + "/ws?name=" + name,
                new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS);
    }
```

Gələn mesajlar `BlockingQueue`-ya yığılır və `poll(5, SECONDS)` ilə gözlənilir. Şəxsi mesaj testində üç istifadəçi var, və üçüncüsünün heç nə **almadığı** yoxlanılır:

```java
aynur.send("/app/private/Rashad", Map.of("text", "gizli"));

assertThat(next(rashadInbox).get("text")).isEqualTo("gizli");
assertThat(next(aynurCopy).get("to")).isEqualTo("Rashad");
assertThat(kamranInbox.poll(500, TimeUnit.MILLISECONDS)).isNull();
```

**Asinxron testlərin tələsi.** `session.subscribe(...)` qayıdanda server abunəliyi hələ qeydə almamış ola bilər. Dərhal `send` etsəniz, mesaj itir və test bəzən keçir, bəzən yox. STOMP-da bunun həlli `RECEIPT`-dir, amma simple broker receipt göndərmir. Modul bunu belə həll edir: abunəlikdən sonra kiçik bir request-reply (`/app/online`) edir. Server bir sessiyanın frame-lərini ardıcıl emal etdiyi üçün (addım 9) cavab gələndə abunəliyin artıq qeydə alındığı dəqiq bilinir.

```bash
./gradlew :websocket-chat:test
```

Yeddi test broadcast-ı, tarixçəni, şəxsi mesajı, onlayn siyahısını, xətanı, `401`-i və xam echo-nu yoxlayır.

## Production-da nələrə diqqət etmək lazımdır

| Mövzu | Problem | Nə etmək |
|---|---|---|
| **Bir neçə instance** | Simple broker yaddaşdadır: A-ya qoşulan istifadəçi B-dəki mesajı görmür | `enableStompBrokerRelay(...)` ilə RabbitMQ (STOMP plugin) və ya ActiveMQ; alternativ olaraq Redis pub/sub ilə öz relay-iniz |
| **Proxy və load balancer** | nginx `Upgrade` header-ini default olaraq ötürmür; boş bağlantını 60 saniyədən sonra bağlayır | `proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_read_timeout 3600s;` |
| **Heartbeat** | Səssiz bağlantını proxy bağlayır; ölü klient saatlarla "onlayn" qalır | STOMP heartbeat (`heart-beat:10000,10000`); simple broker üçün `setHeartbeatValue` + `setTaskScheduler` |
| **Yenidən qoşulma** | Şəbəkə kəsilir, server restart olur | Klientdə backoff və jitter ilə yenidən qoşulma, abunəlikləri bərpa etmək, itən mesajları tarixçədən almaq |
| **Yavaş klient** | Zəif şəbəkəli klientin buferi serverin yaddaşını doldurur | `setSendTimeLimit`, `setSendBufferSizeLimit`; tez-tez dəyişən dəyərlərdə yalnız sonuncunu göndərmək |
| **Təhlükəsizlik** | Başqa saytdan qoşulma (CSWSH), böyük mesajlar, spam | `setAllowedOrigins`, `setMessageSizeLimit`, rate limit, hər `@MessageMapping`-də icazə yoxlaması |
| **Deploy** | Instance söndürüləndə minlərlə bağlantı eyni anda qırılır və hamısı birlikdə qayıdır | Graceful shutdown, klientdə jitter, `least_conn` balanslaşdırma |
| **Etibarlılıq** | Bağlantı qırılanda yolda olan mesajlar itir | Mesajı əvvəlcə bazaya yazmaq, id ilə dublikatları atmaq; kritik əməliyyatları HTTP ilə etmək |

Bir neçə instance üçün konfiqurasiya belə görünür:

```java
registry.enableStompBrokerRelay("/topic", "/queue")
        .setRelayHost("rabbitmq")
        .setRelayPort(61613)
        .setUserDestinationBroadcast("/topic/unresolved-user")   // istifadəçi başqa instance-dadırsa
        .setUserRegistryBroadcast("/topic/user-registry");       // kim harada qoşulub, paylaşılır
```

## Nə vaxt WebSocket seçməməli

- **Server yalnız xəbər verir** (bildiriş, progress bar, LLM cavabı): SSE daha sadədir, adi HTTP-dir, yenidən qoşulma isə daxilidir ([2](02-sse.md), [3](03-yeniden-qosulma.md)).
- **Yeniləmələr nadirdir** (dəqiqədə bir): adi polling kifayətdir və heç bir infrastruktur tələb etmir.
- **Servislər arası əlaqə:** gRPC streaming ([16. Bidirectional streaming](16-bidirectional.md)) və ya Kafka ([20](20-kafka.md)).
- **Mobil tətbiq arxa fonda:** OS WebSocket bağlantısını tez bağlayır; bildiriş üçün push (FCM/APNs) lazımdır.

Seçim cədvəli: [Sonsöz: Hansını nə vaxt seçməli?](18-secim.md).

## Tələlər

- **İstifadəçi adını mesajın gövdəsindən götürmək.** Göndərən həmişə `Principal`-dan müəyyən olunur, klientin dediyindən yox.
- **Yalnız handshake-də yoxlamaq.** Autentifikasiya olunmuş istifadəçi hələ də istənilən destination-a abunə ola bilər. İcazəni hər `SUBSCRIBE` və `SEND` üçün yoxlayın.
- **`Origin`-i yoxlamamaq.** WebSocket-ə CORS tətbiq olunmur; cookie ilə autentifikasiyada başqa sayt istifadəçinin adından qoşula bilər.
- **Simple broker ilə bir neçə instance.** Lokal testdə hər şey işləyir, production-da isə mesajların yarısı itir.
- **Heartbeat-siz production.** Bağlantılar 60 saniyə sükutdan sonra qırılır, "onlayn" siyahısı isə yalan göstərir.
- **Abunəlik qeydə alınmadan göndərmək.** Klient abunə olub dərhal göndərsə, öz mesajını görməyə bilər. Testlər təsadüfi olaraq sınır.
- **`@MessageMapping`-də bloklayan iş.** Yavaş çağırış `clientInboundChannel` pool-unu doldurur və bütün klientlər ləngiyir.
- **Chat-də XSS.** Mesaj mətnini `innerHTML` ilə göstərməyin. Demo səhifə `textContent` istifadə edir.
- **Hər düymə basılışında hadisə göndərmək.** Yalnız vəziyyət dəyişəndə göndərin.

## Yadda saxla

- WebSocket HTTP handshake (`101 Switching Protocols`) ilə başlayan, **iki istiqamətli, uzunömürlü** bağlantıdır.
- Xam WebSocket yalnız frame daşıyır; **STOMP** onun üstünə destination-lar, abunəliklər və xətalar əlavə edir.
- `/app/...` controller-ə, `/topic/...` və `/queue/...` broker-ə, `/user/...` isə konkret istifadəçinin bütün sessiyalarına gedir.
- `@MessageMapping` hamıya yayım, `@SubscribeMapping` ilkin vəziyyət (request-reply), `convertAndSendToUser` şəxsi mesaj, `@SendToUser` isə göndərənə cavab üçündür.
- `Principal` handshake-də təyin olunur; o, həm təhlükəsizliyin, həm də `/user/...` routing-inin əsasıdır.
- Production üçün lazımdır: xarici broker, heartbeat, proxy ayarları, yenidən qoşulma, `Origin` yoxlaması, mesaj limitləri.
- Server yalnız xəbər verirsə, SSE daha sadə seçimdir.

## Tapşırıqlar

1. Səhifəni iki tab-da eyni adla, üçüncü tab-da başqa adla açın. Üçüncü tab-dan birinci ada şəxsi mesaj göndərin. Mesaj neçə tab-da göründü, və niyə?
2. DevTools-da **Network → WS** bölməsini açın. `/ws` sorğusunun status kodunu və response header-lərini tapın. Mesaj yazanda **Messages** tabında hansı frame-lər görünür?
3. Tab-lardan birini bağlayın. İstifadəçi onlayn siyahısından nə vaxt çıxdı? Bəs brauzeri bağlamadan şəbəkəni kəssəniz (DevTools → Network → Offline)?
4. `StompConfig`-də `setPreserveReceiveOrder(true)` sətrini silin və testləri bir neçə dəfə işə salın. Nə dəyişdi?
5. Heartbeat əlavə edin: `enableSimpleBroker(...)`-ə `setHeartbeatValue(new long[]{10000, 10000})` və `setTaskScheduler(...)` yazın, demo səhifədə isə `heart-beat:'0,0'`-ı `'10000,10000'` ilə əvəz edin. Frame logunda nə görünməyə başladı?
6. (Çətin) Otağa üzvlük əlavə edin: istifadəçi yalnız qoşulduğu otaqlara yaza və abunə ola bilsin. Yoxlamanı `SUBSCRIBE` üçün `ChannelInterceptor`-da, `SEND` üçün isə controller-də edin və bunu test ilə yoxlayın.
7. (Çətin) Tətbiqi iki portda işə salın və göstərin ki, bir instance-a qoşulan istifadəçi o birindəki mesajı görmür. Sonra RabbitMQ-nu STOMP plugin-i ilə qaldırıb `enableStompBrokerRelay`-ə keçin.

---

## Müsahibə sualları

WebSocket protokolu, STOMP, Spring-in WebSocket dəstəyi və real-time sistemlərin production problemləri üzrə ən çox verilən 30 sual. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

### WebSocket protokolu

<details>
<summary><b>1. WebSocket nədir və hansı problemi həll edir?</b></summary>

WebSocket bir TCP bağlantısı üzərində **iki istiqamətli (full-duplex), uzunömürlü** kanal yaradan protokoldur (RFC 6455). Bağlantı açıldıqdan sonra həm klient, həm server istədiyi an mesaj göndərə bilər, hər mesaj üçün yeni HTTP sorğusu lazım deyil.

HTTP-də söhbəti yalnız klient başladır: server "yeni mesaj var" deyə özü klientə yaza bilmir. Bundan əvvəl bu problem polling və long polling ilə həll olunurdu: gecikmə, hər sorğuda header-lər və boş cavablar. WebSocket-də isə mesaj dərhal çatır, frame başlığı cəmi 2-14 baytdır.

İstifadə yerləri: chat, bildirişlər, canlı dashboard və birja qiymətləri, onlayn oyunlar, birgə redaktə (Google Docs kimi), canlı yerləşmə (taksi, kuryer).
</details>

<details>
<summary><b>2. WebSocket bağlantısı necə qurulur (handshake)?</b></summary>

Bağlantı adi HTTP/1.1 `GET` sorğusu ilə başlayır:

```
GET /ws?name=Aynur HTTP/1.1
Host: localhost:8086
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13
Sec-WebSocket-Protocol: v12.stomp
Origin: http://localhost:8086
```

Server razıdırsa, belə cavab verir:

```
HTTP/1.1 101 Switching Protocols
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
Sec-WebSocket-Protocol: v12.stomp
```

`Sec-WebSocket-Accept` açarın SHA-1 hash-idir (sabit GUID ilə birlikdə). O, serverin həqiqətən WebSocket-i başa düşdüyünü sübut edir, təhlükəsizlik mexanizmi deyil. `101`-dən sonra eyni TCP bağlantısında HTTP bitir və WebSocket frame-ləri başlayır.

Handshake HTTP olduğu üçün cookie, `Authorization` header-i (brauzerdən yox, bax sual 15), query parametrləri, `Origin` yoxlaması və HTTP status kodu ilə imtina (məs. `401`) bu mərhələdə işləyir. Modulda `RequireNameInterceptor` upgrade-dən əvvəl `401` qaytarır.
</details>

<details>
<summary><b>3. WebSocket frame-ləri hansı növlərdə olur?</b></summary>

- **Data frame-ləri:** `text` (UTF-8) və `binary`. Böyük mesaj bir neçə frame-ə bölünə bilər (`continuation` frame-ləri, sonuncuda `FIN` biti).
- **Control frame-ləri:**
  - `ping` / `pong`: bağlantının canlı olduğunu yoxlamaq; `ping` alan tərəf `pong` ilə cavab verməlidir.
  - `close`: status kodu və səbəb ilə bağlantını bağlamaq.

Klientdən serverə gedən bütün frame-lər **maskalanır** (4 baytlıq təsadüfi açarla XOR). Məqsəd şifrələmə deyil: köhnə proxy-lərin keşini zəhərləmək (cache poisoning) hücumunun qarşısını almaqdır. Serverdən gələn frame-lər maskalanmır.

Brauzer API-si ping/pong-u göstərmir: brauzer ping-ə avtomatik cavab verir, amma JavaScript özü ping göndərə bilməz. Ona görə tətbiq səviyyəsində heartbeat (məs. STOMP heartbeat) istifadə olunur.
</details>

<details>
<summary><b>4. WebSocket bağlantısı necə bağlanır? Close kodları hansılardır?</b></summary>

Düzgün bağlanma: bir tərəf `close` frame-i göndərir, digəri də `close` ilə cavab verir, sonra TCP bağlanır. Ən çox rast gəlinən kodlar:

| Kod | Mənası |
|---|---|
| `1000` | normal bağlanma |
| `1001` | tərəf gedir (tab bağlandı, server söndürülür) |
| `1002` | protokol xətası |
| `1003` | qəbul edilməyən məlumat növü |
| `1006` | **anormal bağlanma**: `close` frame-i olmadan qırıldı (şəbəkə, proxy timeout). Telə göndərilmir, yalnız API-də görünür |
| `1008` | siyasət pozuntusu (məs. icazə yoxdur) |
| `1009` | mesaj çox böyükdür |
| `1011` | server xətası |
| `4000-4999` | tətbiqin öz kodları |

Praktikada `1006` ən vacibidir: o, klientin yenidən qoşulmalı olduğunu göstərir.
</details>

<details>
<summary><b>5. WebSocket, SSE və long polling arasında fərq nədir? Hansını nə vaxt seçərdiniz?</b></summary>

| | Long polling | SSE | WebSocket |
|---|---|---|---|
| İstiqamət | klient soruşur, server cavabı saxlayır | server → klient | hər iki tərəf |
| Protokol | adi HTTP | adi HTTP (`text/event-stream`) | ayrıca protokol (`101` ilə upgrade) |
| Format | istənilən | yalnız mətn | mətn və binary |
| Yenidən qoşulma | tətbiqdə | **daxili** (`Last-Event-ID`) | tətbiqdə |
| HTTP/2 | bəli | bəli, bir bağlantıda çox stream | adətən ayrı TCP bağlantısı |
| Proxy/firewall | problemsiz | adətən problemsiz (buffering-ə diqqət) | bəzən upgrade bloklanır |

Qayda: server yalnız **xəbər verirsə** (bildiriş, progress, LLM cavabı), SSE daha sadədir. Klient də tez-tez və az gecikmə ilə göndərirsə (chat, oyun, birgə redaktə), WebSocket seçilir. Fərq haqqında ətraflı: [2. Server-Sent Events](02-sse.md) və [Sonsöz](18-secim.md).
</details>

<details>
<summary><b>6. <code>ws://</code> ilə <code>wss://</code> fərqi nədir? Production-da hansı istifadə olunur?</b></summary>

`ws://` şifrələnməmiş, `wss://` isə TLS üzərindən WebSocket-dir (HTTP ilə HTTPS kimi). Production-da **həmişə `wss://`**:

- məlumat və token-lər açıq getmir;
- HTTPS səhifədən `ws://`-ə qoşulmaq brauzerdə bloklanır (mixed content);
- korporativ proxy-lər şifrələnməmiş trafikdə `Upgrade` header-ini tez-tez silir və ya bağlantını kəsir; TLS içində isə proxy trafiki görmür, ona görə `wss://` daha etibarlı işləyir.

TLS adətən load balancer-də bitirilir və tətbiqə daxili şəbəkədə `ws://` gəlir.
</details>

### STOMP və Spring

<details>
<summary><b>7. STOMP nədir və WebSocket-in üstündə niyə lazımdır?</b></summary>

Xam WebSocket yalnız frame daşıyır: "bu mesaj hansı otağa aiddir?", "kimə göndərilsin?", "bu xətadır, yoxsa cavab?" kimi sualları tətbiq özü həll etməlidir. Nəticədə hər layihə öz JSON "protokolunu" icad edir.

**STOMP** (Simple Text Oriented Messaging Protocol) bunun üçün hazır, sadə mətn protokoludur. Frame-in əmri, header-ləri və gövdəsi var:

```
SEND
destination:/app/rooms/general
content-type:application/json

{"text":"Salam"}^@
```

Əsas əmrlər: `CONNECT`/`CONNECTED`, `SUBSCRIBE`/`UNSUBSCRIBE`, `SEND`, `MESSAGE`, `ERROR`, `RECEIPT`, `DISCONNECT`, `ACK`/`NACK`.

Spring bunun üstündə tanış modeli qurur: `@MessageMapping` (`@RequestMapping` kimi), `@DestinationVariable` (`@PathVariable` kimi), message converter-lər, exception handler-lər, istifadəçi destination-ları, xarici broker-lə inteqrasiya. Modulda `/ws/echo` (`RawWebSocketConfig`) STOMP-suz variantı müqayisə üçün göstərir.
</details>

<details>
<summary><b>8. Spring-də xam WebSocket ilə STOMP arasında necə seçim edərdiniz?</b></summary>

**Xam WebSocket** (`WebSocketHandler`, `TextWebSocketHandler`):

- öz binary protokolunuz var (oyun, audio, video axını);
- bir-bir əlaqə, abunəlik və yönləndirmə lazım deyil;
- maksimal nəzarət və minimal overhead lazımdır.

**STOMP** (`@EnableWebSocketMessageBroker`):

- pub/sub: otaqlar, mövzular, bildirişlər;
- istifadəçiyə xüsusi mesajlar (`/user/...`);
- bir neçə instance və xarici broker (RabbitMQ);
- Spring Security ilə destination səviyyəsində icazə.

Biznes tətbiqlərinin əksəriyyəti üçün STOMP daha az kod və daha az səhv deməkdir.
</details>

<details>
<summary><b>9. Spring-də STOMP mesajı hansı yolla keçir? <code>/app</code>, <code>/topic</code>, <code>/queue</code> prefiksləri nə deməkdir?</b></summary>

```java
registry.enableSimpleBroker("/topic", "/queue");
registry.setApplicationDestinationPrefixes("/app");
registry.setUserDestinationPrefix("/user");
```

- `/app/...`: mesaj `@MessageMapping` metoduna gedir (controller). Metod nəticəni broker-ə göndərə bilər.
- `/topic/...`, `/queue/...`: mesaj birbaşa broker-ə gedir və həmin destination-a abunə olan hər kəsə paylanır. Konvensiyaya görə `/topic` hamıya yayım (broadcast), `/queue` isə bir alıcı üçündür. Simple broker üçün bu fərq yalnız ad səviyyəsindədir.
- `/user/...`: istifadəçiyə xüsusi destination-lar (sual 11).

Daxildə üç kanal var: `clientInboundChannel` (klientdən gələnlər), `brokerChannel` (tətbiqdən broker-ə) və `clientOutboundChannel` (klientlərə gedənlər). Interceptor-lar bu kanallara qoşulur.
</details>

<details>
<summary><b>10. <code>@SendTo</code>, <code>SimpMessagingTemplate</code> və <code>@SubscribeMapping</code> fərqi nədir?</b></summary>

- **`@SendTo("/topic/...")`:** metodun qaytardığı dəyər həmin destination-a yayımlanır. Qeyd olunmayıbsa, default olaraq `/topic` + gələn destination istifadə olunur.
- **`SimpMessagingTemplate`:** koddan istənilən yerdən və istənilən vaxt göndərmək üçündür: `convertAndSend("/topic/rooms/general", msg)`, `convertAndSendToUser(user, "/queue/private", msg)`. Məsələn, Kafka listener-indən və ya planlaşdırılmış tapşırıqdan bildiriş göndərmək.
- **`@SubscribeMapping`:** klient abunə olanda metodun nəticəsi **yalnız həmin klientə**, bir dəfə göndərilir, broker-dən keçmir. İlkin vəziyyət üçün idealdır. Modulda o, son 50 mesajı qaytarır: `@SubscribeMapping("/rooms/{room}/history")`.
</details>

<details>
<summary><b>11. Bir konkret istifadəçiyə mesajı necə göndərmək olar?</b></summary>

```java
messaging.convertAndSendToUser("Rashad", "/queue/private", message);
```

Klient `/user/queue/private`-ə abunə olur. `UserDestinationMessageHandler` bunu hər sessiya üçün unikal destination-a çevirir (`/queue/private-user3xk2` kimi). Nəticədə mesaj Rashad-ın **bütün sessiyalarına** (bütün tab və cihazlarına) çatır, başqa heç kim ona abunə ola bilməz.

Bunun üçün sessiyanın `Principal`-ı olmalıdır: istifadəçi adı oradan götürülür. Modulda `NameHandshakeHandler` onu handshake URL-indən təyin edir; real layihədə Spring Security təyin edir.

`@SendToUser` isə cavabı yalnız mesajı **göndərən** istifadəçiyə qaytarır. Default olaraq istifadəçinin bütün sessiyalarına gedir; yalnız göndərən sessiya üçün `broadcast = false`.
</details>

<details>
<summary><b>12. WebSocket mesajının emalında baş verən xətanı necə idarə etmək lazımdır?</b></summary>

`@MessageExceptionHandler`, `@ExceptionHandler`-in analoqudur. Nəticəni `@SendToUser` ilə yalnız göndərənə qaytarmaq olar:

```java
@MessageExceptionHandler
@SendToUser("/queue/errors")
ErrorMessage handle(IllegalArgumentException e) { ... }
```

Mərkəzləşdirilmiş idarə üçün `@ControllerAdvice` sinfində də yazıla bilər. Xətanı otağa yayımlamaq olmaz: digər istifadəçilər başqasının xətasını görməməlidir. Tutulmayan xəta isə klientə STOMP `ERROR` frame-i kimi gedə və bağlantını bağlaya bilər.

Modulda boş mesaj göndərəndə xəta yalnız göndərənin `/user/queue/errors` abunəliyinə gəlir (`invalidMessageReturnsAnErrorOnlyToTheSender` testi).
</details>

<details>
<summary><b>13. Bir klientin mesajları serverdə hansı ardıcıllıqla emal olunur?</b></summary>

Default olaraq `clientInboundChannel` thread pool-dur və **bir sessiyanın mesajları paralel** emal oluna bilər. Məsələn, `SUBSCRIBE` və ondan dərhal sonra gələn `SEND` fərqli ardıcıllıqla işlənə bilər: klient öz mesajının yayımını buraxa bilər.

```java
registry.setPreserveReceiveOrder(true);                          // gələnlər
registry.setPreservePublishOrder(true);  // MessageBrokerRegistry-də: klientə gedənlər
```

Birincisi bir sessiyanın gələn frame-lərini göndərildiyi ardıcıllıqla, ikincisi klientə gedən mesajları broker-in dərc etdiyi ardıcıllıqla çatdırır. Qiyməti: bir sessiya daxilində paralellik itir. Ardıcıllıq vacib olan yerlərdə (chat, sənəd redaktəsi) yandırılır.
</details>

<details>
<summary><b>14. Kimin onlayn olduğunu necə izləmək olar?</b></summary>

Spring bütün sessiyalar üçün application event-ləri dərc edir: `SessionConnectEvent`, `SessionConnectedEvent`, `SessionSubscribeEvent`, `SessionUnsubscribeEvent`, `SessionDisconnectEvent`. `@EventListener` ilə tutulur.

Nəzərə alınmalı məqamlar:

- **Bir istifadəçi = çox sessiya** (tab, telefon). Sessiyaları saymaq lazımdır: sonuncu sessiya bağlananda istifadəçi oflayn olur. Modulda `Presence` sinfi belə edir.
- `SessionDisconnectEvent` bir sessiya üçün **bir neçə dəfə** gələ bilər; handler idempotent olmalıdır.
- Şəbəkə səssizcə kəsiləndə disconnect yalnız heartbeat timeout-undan sonra aşkarlanır.
- Bir neçə instance-da onlayn siyahısı yaddaşda yox, ortaq yerdə (Redis) saxlanmalıdır. Spring Session bunun üçün hazır `SimpUserRegistry` implementasiyası verir.
</details>

### Təhlükəsizlik

<details>
<summary><b>15. WebSocket bağlantısında autentifikasiya necə edilir?</b></summary>

İki yer var:

1. **Handshake (HTTP)**: cookie/HTTP sessiyası və ya query-dəki token. Spring Security handshake sorğusunu adi HTTP sorğusu kimi yoxlayır və `Principal`-ı sessiyaya bağlayır. `HandshakeInterceptor` burada `401` qaytara bilər.
2. **STOMP `CONNECT` frame-i**: token `Authorization` header-ində göndərilir və `clientInboundChannel`-dəki `ChannelInterceptor` onu yoxlayır:

```java
if (StompCommand.CONNECT.equals(accessor.getCommand())) {
    Authentication auth = jwtAuth(accessor.getFirstNativeHeader("Authorization"));
    accessor.setUser(auth);
}
```

Brauzerin `WebSocket` API-si handshake-ə xüsusi header əlavə etməyə imkan vermir, ona görə JWT ya `CONNECT` frame-ində, ya da (ən pis halda) query-də göndərilir.
</details>

<details>
<summary><b>16. JWT-ni query parametrində göndərməyin riski nədir?</b></summary>

URL server access log-larına, proxy log-larına, brauzer tarixçəsinə və monitorinq sistemlərinə düşür, ona görə token sızır. Alternativlər:

- token-i STOMP `CONNECT` frame-ində göndərmək (ən yaxşısı);
- `HttpOnly` cookie ilə sessiya (eyni domen olduqda);
- **qısaömürlü bilet (ticket):** klient adi HTTP ilə (`Authorization` header-i ilə) 30 saniyəlik birdəfəlik bilet alır və onu query-də göndərir; bilet istifadədən sonra etibarsız olur.

Əlavə problem: uzun bağlantı ərzində token-in vaxtı keçə bilər. Server bağlantını token-in bitmə vaxtında bağlamalı və ya klientdən yeni token istəməlidir; əks halda bir saatlıq token həftələrlə işləyən bağlantı verir.
</details>

<details>
<summary><b>17. Cross-Site WebSocket Hijacking (CSWSH) nədir və necə qorunmaq olar?</b></summary>

WebSocket handshake-inə **CORS tətbiq olunmur**. Autentifikasiya cookie ilədirsə, istifadəçi `evil.com`-u açanda o sayt istifadəçinin brauzerindən `wss://bank.com/ws`-ə qoşula bilər: brauzer cookie-ni avtomatik göndərir və bağlantı istifadəçinin adından açılır. Bu, CSRF-in WebSocket variantıdır.

Qorunma yolları:

- **`Origin` header-ini yoxlamaq:** `registry.addEndpoint("/ws").setAllowedOrigins("https://app.example.com")`. Spring default olaraq yalnız eyni origin-ə icazə verir;
- `SameSite` cookie;
- cookie əvəzinə `CONNECT` frame-indəki token;
- Spring Security STOMP `CONNECT` üçün də CSRF token tələb edir.
</details>

<details>
<summary><b>18. Destination səviyyəsində avtorizasiya necə edilir?</b></summary>

Autentifikasiya "kimsən?" sualına cavab verir, amma istifadəçi hələ də istənilən destination-a `SUBSCRIBE` və ya `SEND` edə bilər: `/topic/rooms/secret-board`, başqasının `/queue/...`-su. Spring Security-də mesaj səviyyəsində qaydalar yazılır:

```java
@Bean
AuthorizationManager<Message<?>> messageAuthorizationManager(
        MessageMatcherDelegatingAuthorizationManager.Builder messages) {
    return messages
            .simpDestMatchers("/app/admin/**").hasRole("ADMIN")
            .simpSubscribeDestMatchers("/topic/rooms/**").authenticated()
            .anyMessage().denyAll()
            .build();
}
```

`@EnableWebSocketSecurity` bunu aktiv edir. Dinamik yoxlamalar (istifadəçi həqiqətən bu otağın üzvüdürmü?) `@MessageMapping` metodunda və ya `SUBSCRIBE` üçün `ChannelInterceptor`-da edilir. Qayda: default `denyAll`, icazəni açıq verin.
</details>

<details>
<summary><b>19. WebSocket serverini sui-istifadədən necə qorumaq olar?</b></summary>

- **Mesaj ölçüsü:** `registration.setMessageSizeLimit(64 * 1024)`; böyük mesaj yaddaşı doldurur.
- **Göndərmə buferi və vaxtı:** `setSendBufferSizeLimit`, `setSendTimeLimit` (sual 21).
- **Rate limit:** bir sessiyanın saniyədə neçə mesaj göndərə biləcəyi (`ChannelInterceptor`-da token bucket).
- **Bağlantı limiti:** bir istifadəçi və ya IP-dən neçə bağlantı açıla bilər; load balancer səviyyəsində də.
- **Validasiya:** gələn JSON-u `@Valid` ilə yoxlayın, HTML-i escape edin (chat-da XSS klassik hücumdur).
- **Boş bağlantılar:** heartbeat ilə ölü bağlantıları bağlayın.
</details>

### Miqyas və production

<details>
<summary><b>20. Tətbiq bir neçə instance-da işləyəndə WebSocket necə miqyaslanır?</b></summary>

Problem: Aynur instance A-ya, Rashad isə instance B-yə qoşulub. Simple broker yaddaşdadır, ona görə A-dakı `convertAndSend` B-dəki abunəçilərə çatmır.

Həll yolları:

- **Xarici STOMP broker relay:** `registry.enableStompBrokerRelay("/topic", "/queue")`. RabbitMQ (STOMP plugin) və ya ActiveMQ bütün instance-lar üçün ortaq broker olur, Spring ona TCP ilə qoşulur və mesajları ötürür. İstifadəçi destination-ları üçün `setUserDestinationBroadcast` və `setUserRegistryBroadcast` istifadəçinin hansı instance-da olduğunu instance-lar arasında paylaşır.
- **Redis pub/sub və ya Kafka ilə öz relay-iniz:** hər instance mövzuya abunə olur və gələn mesajı öz lokal klientlərinə göndərir.
- **İdarə olunan servislər:** AWS API Gateway WebSocket, Azure Web PubSub, Pusher, Ably.

Sticky session tək başına problemi həll etmir: o, yalnız bir klientin öz bağlantısını eyni instance-da saxlayır, amma iki fərqli istifadəçi fərqli instance-larda ola bilər.
</details>

<details>
<summary><b>21. Yavaş klient (slow consumer) problemi nədir?</b></summary>

Server mesajları hər klientə ayrıca göndərir. Klientin şəbəkəsi yavaşdırsa (mobil, zəif Wi-Fi), mesajlar serverdə həmin sessiyanın buferində yığılır. Minlərlə belə klient yaddaşı doldura bilər, bir klientə yazmaq isə thread-i bloklaya bilər.

Spring-də limitlər:

```java
registration.setSendTimeLimit(15_000)             // bir göndərmə nə qədər çəkə bilər
            .setSendBufferSizeLimit(512 * 1024);  // sessiyanın buferi
```

Limit aşılanda sessiya bağlanır. Xam WebSocket-də isə `ConcurrentWebSocketSessionDecorator` eyni işi görür (overflow strategiyası: bağlamaq və ya köhnə mesajları atmaq).

Dizayn səviyyəsində: tez-tez yenilənən məlumatda (qiymət, yerləşmə) aralıq dəyərləri atmaq və yalnız sonuncunu göndərmək (conflation) klientə hər dəyişikliyi göndərməkdən yaxşıdır.
</details>

<details>
<summary><b>22. Heartbeat nə üçün lazımdır?</b></summary>

İki problemi həll edir:

1. **Səssiz bağlantının kəsilməsi:** load balancer-lər, proxy-lər və NAT boş bağlantını bir müddətdən sonra (məs. AWS ALB default 60 s, nginx `proxy_read_timeout` default 60 s) xəbərsiz bağlayır.
2. **Ölü klientin aşkarlanması:** telefon tunelə girdi, TCP `FIN` gəlmədi. Heartbeat olmadan server sessiyanı saatlarla açıq saxlayır və "onlayn" göstərir.

STOMP heartbeat `CONNECT`/`CONNECTED` frame-lərində razılaşdırılır: `heart-beat:10000,10000` (göndərirəm, gözləyirəm, ms ilə). Simple broker-də server heartbeat-i üçün `TaskScheduler` verilməlidir: `enableSimpleBroker(...).setHeartbeatValue(...).setTaskScheduler(...)`. Heartbeat intervalı proxy-nin idle timeout-undan kiçik olmalıdır.
</details>

<details>
<summary><b>23. Klient bağlantı qırılanda nə etməlidir?</b></summary>

WebSocket-də SSE-dəki `Last-Event-ID` kimi hazır mexanizm yoxdur, bu tamamilə tətbiqin işidir:

1. **Avtomatik yenidən qoşulma**, **eksponensial backoff və jitter** ilə (1 s, 2 s, 4 s... + təsadüfi əlavə). Jitter olmasa, server restart olanda on minlərlə klient eyni saniyədə qayıdır (thundering herd).
2. **Abunəlikləri bərpa etmək:** yeni bağlantıda əvvəlki `SUBSCRIBE`-lar yoxdur.
3. **İtirilən mesajları almaq:** klient son aldığı mesajın id-sini və ya vaxtını göndərir, server tarixçədən çatışmayanları qaytarır (`@SubscribeMapping` və ya REST ilə).
4. Göndərilməmiş mesajları növbədə saxlayıb təkrar göndərmək və dublikatların qarşısını almaq üçün mesaja klient id-si vermək (idempotentlik).

`@stomp/stompjs` 1-ci və 2-ci addımları `reconnectDelay` ilə özü edir; 3-cü və 4-cü addımlar hər zaman sizin məsuliyyətinizdir.
</details>

<details>
<summary><b>24. WebSocket mesajların çatdırılmasına zəmanət verirmi?</b></summary>

TCP bir bağlantı daxilində sıranı və çatdırılmanı təmin edir, amma **bağlantı qırılanda** yolda olan mesajlar itə bilər. `send()` çağırışının uğurlu olması mesajın qarşı tərəfə çatdığı demək deyil, o, yalnız buferə yazılıb.

Zəmanət lazımdırsa:

- **Klient → server:** STOMP `receipt` header-i: server emal edəndən sonra `RECEIPT` frame-i göndərir. Simple broker receipt-i dəstəkləmir, broker relay (RabbitMQ) dəstəkləyir. Alternativ: tətbiq səviyyəsində "ack" mesajı.
- **Server → klient:** mesajları bazada saxlayıb yenidən qoşulmada çatdırmaq (sual 23), və ya broker-in `ACK`/`NACK` rejimi.
- Hər mesaja unikal id verib alıcı tərəfdə dublikatları atmaq (at-least-once + idempotentlik).

Kritik məlumat (ödəniş, sifariş) WebSocket-lə deyil, adi HTTP və ya Kafka ilə göndərilir; WebSocket isə yalnız "yeniləndi, gəl bax" siqnalı üçün istifadə olunur.
</details>

<details>
<summary><b>25. WebSocket-i nginx və ya load balancer arxasında işə salarkən nələrə diqqət etmək lazımdır?</b></summary>

nginx `Upgrade` header-ini default olaraq ötürmür:

```nginx
location /ws {
    proxy_pass http://app;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_read_timeout 3600s;
}
```

- **Idle timeout:** heartbeat intervalından böyük olmalıdır.
- **Load balancing alqoritmi:** bağlantılar uzunömürlüdür, ona görə round robin yük bərabərsizliyinə səbəb olur (yeni instance heç bağlantı almır). `least_conn` daha yaxşıdır.
- **Deploy:** instance söndürüləndə minlərlə bağlantı eyni anda qırılır və hamısı başqa instance-a qaçır. Graceful shutdown, bağlantıları mərhələlərlə bağlamaq və klientdə jitter lazımdır.
- **Fayl deskriptorları:** hər bağlantı bir socket-dir; OS limitini (`ulimit -n`) artırın.
- **Kubernetes Ingress:** timeout annotasiyaları (`nginx.ingress.kubernetes.io/proxy-read-timeout`).
</details>

<details>
<summary><b>26. Bir serverdə neçə WebSocket bağlantısı saxlamaq olar? Thread modeli necə təsir edir?</b></summary>

Bağlantı öz-özlüyündə ucuzdur: boş bağlantı əsasən socket və bufer yaddaşıdır (bir neçə-onlarla KB). Non-blocking I/O (Tomcat NIO, Netty) ilə bir server on minlərlə, düzgün tənzimləmə ilə yüz minlərlə bağlantı saxlaya bilər; hər bağlantıya bir thread **ayrılmır**.

Məhdudlaşdıran amillər: yaddaş (buferlər, sessiya vəziyyəti), fayl deskriptorları, mesaj trafiki (broadcast: 10 000 abunəçili otağa bir mesaj = 10 000 göndərmə), CPU (JSON serializasiya, TLS).

Thread-lər mesaj **emalında** istifadə olunur. `@MessageMapping` metodunda bloklayan çağırış (bazaya, xarici API-yə) `clientInboundChannel` pool-unu doldurur və bütün klientlər ləngiyir. Pool-u `configureClientInboundChannel` ilə tənzimləmək, ağır işi başqa executor-a vermək və ya virtual thread-lərdən istifadə etmək olar.
</details>

<details>
<summary><b>27. SockJS nədir və bu gün hələ lazımdırmı?</b></summary>

SockJS WebSocket-in işləmədiyi mühitlər üçün fallback kitabxanasıdır: WebSocket alınmasa, HTTP streaming və ya long polling ilə eyni API-ni təqdim edir. Spring-də: `registry.addEndpoint("/ws").withSockJS()`.

2010-cu illərdə köhnə brauzerlər (IE 9 və əvvəlki) və upgrade-i bloklayan korporativ proxy-lər səbəbindən vacib idi. Bu gün bütün brauzerlər WebSocket-i dəstəkləyir, `wss://` isə proxy problemlərinin çoxunu həll edir. Ona görə yeni layihələrdə SockJS adətən istifadə olunmur, amma çox məhdud korporativ şəbəkələrdə işləyən tətbiqlər üçün hələ də düşünülə bilər.
</details>

### Test, dizayn, alternativlər

<details>
<summary><b>28. WebSocket/STOMP endpoint-ini necə test etmək olar?</b></summary>

- **İnteqrasiya testi:** `@SpringBootTest(webEnvironment = RANDOM_PORT)` real server qaldırır, `WebSocketStompClient` (+ `StandardWebSocketClient`) ilə qoşulur, abunə olur, göndərir, gələn mesajları `BlockingQueue`-ya yığıb `poll(timeout)` ilə yoxlayır. Modulun testləri belədir: broadcast, tarixçə, şəxsi mesaj, onlayn siyahısı, xəta, `401`, xam echo.
- **Asinxronluğa diqqət:** "mesaj gəlmədi" iddiasını yoxlamaq üçün qısa `poll` kifayətdir, amma "abunəlik qeydə alınıb" anını bilmək çətindir. Modulda abunəlikdən sonra kiçik bir request-reply edilir; cavab gələndə abunəliyin artıq qeydə alındığı dəqiq bilinir.
- **Unit test:** `@MessageMapping` metodu adi metoddur, `SimpMessagingTemplate` mock edilə bilər.
- **Yük testi:** Gatling, k6, Artillery WebSocket ssenariləri ilə.
- **Əl ilə:** brauzerin DevTools-unda Network → WS → Messages frame-ləri göstərir; `websocat` komanda sətri aləti.
</details>

<details>
<summary><b>29. Chat sistemini sıfırdan dizayn etsəniz, arxitektura necə olardı?</b></summary>

Sistem dizaynı müsahibələrinin klassik sualıdır. Qısa cavab:

1. **Bağlantı qatı (gateway):** WebSocket bağlantılarını saxlayan instance-lar, onları load balancer (`least_conn`) paylaşır. Kim hansı instance-dadır, Redis-də saxlanılır.
2. **Mesajın yolu:** klient mesajı göndərir → servis onu **əvvəlcə bazada saxlayır** (Cassandra/ScyllaDB və ya PostgreSQL; `chat_id + zaman` üzrə partition) → Kafka və ya Redis pub/sub vasitəsilə alıcının qoşulduğu instance-a çatdırılır → WebSocket ilə alıcıya.
3. **Alıcı oflayndırsa:** push bildiriş (FCM/APNs); o, qoşulanda son oxuduğu mesajdan sonrakıları alır.
4. **Mesaj id-si və sıra:** hər chat daxilində artan id (və ya Snowflake); klient dublikatları id ilə atır.
5. **Status:** göndərildi / çatdı / oxundu, ayrıca ack mesajları ilə.
6. **Onlayn statusu:** heartbeat + Redis-də TTL-li açar.
7. **Media:** faylı WebSocket-lə yox, pre-signed URL ilə obyekt saxlama yerinə (S3) yükləmək; mesajda yalnız link.

Bu modul 1-ci və 2-ci addımların sadələşdirilmiş, bir instance-lıq variantıdır.
</details>

<details>
<summary><b>30. Spring MVC (Servlet) və WebFlux-da WebSocket dəstəyi arasında fərq nədir? RSocket nədir?</b></summary>

- **Servlet stack (spring-websocket):** `WebSocketHandler`, STOMP (`@EnableWebSocketMessageBroker`), SockJS, simple broker və broker relay. STOMP dəstəyi tam məhz buradadır; bu modul belə qurulub.
- **WebFlux:** reaktiv `WebSocketHandler`: `session.receive()` `Flux<WebSocketMessage>` qaytarır, `session.send(Flux)` isə göndərir. Backpressure və reaktiv operatorlar təbii işləyir, amma STOMP və `@MessageMapping` + broker dəstəyi **yoxdur**: pub/sub-u özünüz qurursunuz (məs. `Sinks.Many` ilə).
- **RSocket:** reaktiv, binary tətbiq protokoludur (TCP və ya WebSocket üzərində). Dörd qarşılıqlı əlaqə modeli (request-response, fire-and-forget, request-stream, channel) və protokol səviyyəsində backpressure (`request(n)`) verir. Spring-də `@MessageMapping` ilə istifadə olunur, amma ekosistemi kiçikdir və geniş yayılmayıb.

Çox bağlantılı, reaktiv məntiqli sistem üçün WebFlux, klassik chat/bildiriş üçün isə Servlet + STOMP daha sadə yoldur.
</details>

---

[← 25. Spring AI](25-spring-ai.md) · [Mündəricat](README.md)
