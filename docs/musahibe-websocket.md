# Müsahibə sualları: WebSocket və STOMP

[Mündəricat](README.md) · Modul: [`websocket-chat`](../websocket-chat/README.md) (http://localhost:8086)

WebSocket protokolu, STOMP, Spring-in WebSocket dəstəyi və real-time sistemlərin production problemləri üzrə ən çox verilən 30 sual. Kod nümunələri [`websocket-chat`](../websocket-chat) modulundandır. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

---

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

[Mündəricat](README.md) · Modul: [websocket-chat](../websocket-chat/README.md)
