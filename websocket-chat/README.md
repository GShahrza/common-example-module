# websocket-chat: WebSocket və STOMP ilə real-time chat

Serverin **istədiyi an** klientə mesaj göndərməsi, klientin də eyni bağlantı üzərindən cavab verməsi: chat, bildirişlər, canlı dashboard, birgə redaktə, onlayn oyun.

| Mövzu | Harada |
|---|---|
| STOMP endpoint və message broker | `StompConfig` |
| Otaqlar (broadcast) | `@MessageMapping("/rooms/{room}")` → `/topic/rooms/{room}` |
| Şəxsi mesaj (bir istifadəçinin bütün tab-larına) | `convertAndSendToUser` → `/user/queue/private` |
| Request-reply (tarixçə) | `@SubscribeMapping` |
| Kim onlayndır | `SessionConnectedEvent` / `SessionDisconnectEvent` |
| "yazır..." göstəricisi | saxlanmayan, ötürülən event |
| Xətanı yalnız göndərənə qaytarmaq | `@MessageExceptionHandler` + `@SendToUser` |
| Handshake-də autentifikasiya | `HandshakeInterceptor`, `HandshakeHandler` |
| Müqayisə: STOMP-suz, xam WebSocket | `RawWebSocketConfig` (`/ws/echo`) |

## İşə salma qaydası

Heç bir xarici servis lazım deyil.

```bash
./gradlew :websocket-chat:bootRun
```

və ya Docker ilə:

```bash
docker build -f websocket-chat/Dockerfile -t websocket-chat .
docker run --rm -p 8086:8086 websocket-chat
```

Sonra **http://localhost:8086** səhifəsini **iki müxtəlif tab-da** (və ya iki brauzerdə) açın, fərqli adlarla qoşulun və yazışın:

- sağdakı "Onlayn" siyahısından istifadəçiyə klikləsəniz, mesaj şəxsi gedir;
- yazarkən o biri tab-da "... yazır…" görünür;
- tab-ı bağlasanız, istifadəçi onlayn siyahısından çıxır;
- səhifənin aşağısında brauzerin göndərdiyi və aldığı STOMP frame-ləri görünür.

---

## Necə işləyir

### Niyə WebSocket?

| | HTTP polling | SSE | WebSocket |
|---|---|---|---|
| İstiqamət | klient soruşur | server → klient | **hər iki tərəf** |
| Bağlantı | hər sorğu yeni request | bir uzun HTTP cavabı | bir TCP bağlantısı |
| Gecikmə | polling intervalı qədər | dərhal | dərhal |
| Nə vaxt | nadir yeniləmə | bildiriş, LLM cavabı, progress | chat, oyun, birgə redaktə |

Server yalnız "xəbər verirsə" (bildiriş, progress), SSE kifayətdir və daha sadədir (bax: [mvc-streaming](../mvc-streaming/README.md)). Klient də tez-tez mesaj göndərirsə, WebSocket seçilir.

Bağlantı adi HTTP sorğusu ilə başlayır: `GET /ws` və `Upgrade: websocket`. Server `101 Switching Protocols` cavabını verir və bundan sonra eyni TCP bağlantısı iki tərəfli mesaj kanalına çevrilir.

### Niyə STOMP?

Xam WebSocket yalnız mətn və ya binary frame-ləri daşıyır: "bu mesaj hansı otağa aiddir?", "kimə göndərilsin?", "bu xətadır, yoxsa cavab?" kimi suallara cavab vermir. `/ws/echo` bunu göstərir: hər şeyi öz JSON formatınızla icad etməli olardınız.

**STOMP** bunun üçün sadə mətn protokoludur (HTTP-yə bənzəyir):

```
SEND
destination:/app/rooms/general
content-type:application/json

{"text":"Salam"}^@
```

Spring bunun üstündə tanış modeli qurur: `@MessageMapping` (`@RequestMapping` kimi), `@DestinationVariable` (`@PathVariable` kimi), message converter-lər, exception handler-lər. Demo səhifədəki klient STOMP-u kitabxanasız, ~20 sətirlə həyata keçirir ki, protokolun nə qədər sadə olduğu görünsün. Real layihədə `@stomp/stompjs` istifadə olunur.

### Mesajın yolu

```
brauzer ──SEND /app/rooms/general──► @MessageMapping("/rooms/{room}")
                                         │ tarixçəyə yaz
                                         ▼
                             convertAndSend("/topic/rooms/general")
                                         │
                                  Simple broker
                                         │  bu destination-a abunə olan hər sessiyaya
                          ┌──────────────┼──────────────┐
                          ▼              ▼              ▼
                       Aynur           Rashad         Kamran
```

- `/app/...` prefiksli mesajlar controller-ə gedir.
- `/topic/...` və `/queue/...` isə birbaşa broker-ə gedir.

### Şəxsi mesajlar: `/user/...`

```java
messaging.convertAndSendToUser("Rashad", "/queue/private", message);
```

Klient `/user/queue/private` destination-a abunə olur. Spring bunu hər sessiya üçün unikal ünvana çevirir (məs. `/queue/private-user3xk2`). Nəticədə mesaj yalnız **Rashad-ın bütün sessiyalarına** (bütün tab və cihazlarına) çatır; başqa heç kim onu görə bilmir (testdə yoxlanılır).

İstifadəçinin kim olduğunu `Principal` müəyyən edir. Burada o, handshake URL-indəki `?name=`-dən götürülür (`NameHandshakeHandler`); ad yoxdursa, `RequireNameInterceptor` upgrade-dən əvvəl `401` qaytarır. Real layihədə `Principal`-ı Spring Security təyin edir: HTTP sessiyası, cookie və ya `CONNECT` frame-indəki JWT (`ChannelInterceptor` ilə).

### Request-reply: `@SubscribeMapping`

```java
@SubscribeMapping("/rooms/{room}/history")
List<ChatMessage> history(@DestinationVariable String room)
```

Klient `/app/rooms/general/history` destination-a abunə olanda metodun nəticəsi **yalnız həmin klientə**, bir dəfə göndərilir və broker-dən keçmir. Bu, "qoşulanda ilkin vəziyyəti al" üçün idealdır: son 50 mesaj və onlayn siyahısı.

### Onlayn siyahısı

`SessionConnectedEvent` və `SessionDisconnectEvent` bütün sessiyalar üçün gəlir. Disconnect hadisəsi brauzer bağlananda və ya şəbəkə kəsiləndə də gəlir: server TCP bağlantısının bağlandığını, ya da heartbeat-lərin gəlmədiyini görür. İstifadəçinin bir neçə tab-ı ola bilər, ona görə sessiyalar sayılır: sonuncu tab bağlananda istifadəçi oflayn olur.

### Sıra

Default olaraq bir klientin mesajları server tərəfdə **paralel** emal oluna bilər. Nəticədə `SUBSCRIBE` və ondan dərhal sonra gələn `SEND` fərqli ardıcıllıqla işlənə bilər. `registry.setPreserveReceiveOrder(true)` bir sessiyanın frame-lərini göndərildiyi ardıcıllıqla emal edir. Testlər bundan istifadə edir: abunəlikdən sonra kiçik bir request-reply edir və cavab gələndə abunəliyin qeydə alındığı dəqiq bilinir.

## Production üçün

- **Bir neçə instance.** Simple broker yaddaşdadır: instance A-ya qoşulan istifadəçi instance B-dəki mesajı görməz. Həll `enableStompBrokerRelay(...)` ilə xarici broker-dir (RabbitMQ STOMP plugin, ActiveMQ); bütün instance-lar mesajları onun vasitəsilə paylaşır. Alternativ: Redis pub/sub ilə öz relay-iniz.
- **Load balancer.** WebSocket uzunömürlü bağlantıdır. Proxy-də (nginx: `proxy_set_header Upgrade`, `Connection "upgrade"`) upgrade-i və idle timeout-u (`proxy_read_timeout`) düzgün qurun.
- **Heartbeat.** Bəzi proxy-lər səssiz bağlantını 60 saniyədən sonra bağlayır. STOMP heartbeat (`heart-beat:10000,10000`) həm bağlantını canlı saxlayır, həm də ölü klientləri aşkar edir.
- **Təhlükəsizlik.** Handshake-də autentifikasiya edin, `Origin` header-ini yoxlayın (`setAllowedOrigins`) və mesaj ölçüsünü məhdudlaşdırın (`setMessageSizeLimit`). Hər `@MessageMapping`-də icazəni yoxlayın: istifadəçi həqiqətən bu otağın üzvüdür?
- **Yenidən qoşulma.** Şəbəkə kəsiləndə klient özü yenidən qoşulmalı və itirdiyi mesajları tarixçədən almalıdır. WebSocket-də SSE-dəki `Last-Event-ID` kimi hazır mexanizm yoxdur.

## Testlər

Testlər real server qaldırır və Spring-in `WebSocketStompClient`-i ilə qoşulur:

```bash
./gradlew :websocket-chat:test
```

| Test | Nəyi yoxlayır |
|---|---|
| `roomMessageIsBroadcastToAllSubscribers` | otağa göndərilən mesajı bütün abunəçilər alır |
| `lateJoinerGetsTheHistoryOnSubscribe` | `@SubscribeMapping` ilə tarixçə |
| `privateMessageReachesOnlyTheRecipient` | şəxsi mesaj üçüncü şəxsə çatmır |
| `presenceFollowsConnectAndDisconnect` | onlayn siyahısı qoşulma və ayrılmanı izləyir |
| `invalidMessageReturnsAnErrorOnlyToTheSender` | `@MessageExceptionHandler` → `/user/queue/errors` |
| `handshakeWithoutNameIsRejected` | ad olmadan handshake `401` alır |
| `rawWebSocketEchoesTextFrames` | xam WebSocket handler |
