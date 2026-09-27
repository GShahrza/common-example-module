# Müsahibə sualları: III hissə, gRPC

[Mündəricat](README.md) · Fəsillər: [12](12-grpc-nedir.md) · [13](13-unary.md) · [14](14-server-streaming.md) · [15](15-client-streaming.md) · [16](16-bidirectional.md) · [17](17-brauzer-ve-gateway.md)

gRPC, protobuf və HTTP/2 üzrə ən çox verilən 30 sual. Cavabı açmazdan əvvəl özünüz cavab verməyə çalışın.

---

### Əsaslar: gRPC, protobuf, HTTP/2

<details>
<summary><b>1. gRPC nədir və hansı problemi həll edir?</b></summary>

gRPC Google-un açıq mənbəli RPC (remote procedure call) framework-üdür. Servis və mesajlar `.proto` faylında təsvir olunur, bu fayldan müxtəlif dillər üçün client stub-ları və server skeletləri generasiya edilir. Nəticədə başqa servisi çağırmaq adi metod çağırmağa bənzəyir: `stub.getOrder(request)`.

Həll etdiyi problemlər:

- **Müqavilə:** API-nin forması kodda deyil, `.proto`-da yazılır, hər iki tərəf ondan generasiya olunur. "Sahənin adı `orderId` idi, yoxsa `order_id`?" problemi yoxdur.
- **Sürət:** binary protobuf JSON-dan kiçik və tez parse olunur; HTTP/2 bir bağlantıda çox sorğu daşıyır.
- **Streaming:** dörd çağırış növü dilin özündə var.
- **Çoxdilli mühit:** Java servisi Go, Python, C++ və ya Kotlin servisi ilə eyni müqavilə üzərindən danışır.
</details>

<details>
<summary><b>2. gRPC ilə REST arasında fərq nədir? Hansını nə vaxt seçərdiniz?</b></summary>

| | REST (JSON/HTTP) | gRPC |
|---|---|---|
| Müqavilə | İstəyə bağlı (OpenAPI) | Məcburi (`.proto`) |
| Format | Mətn (JSON), oxunaqlı | Binary (protobuf), kompakt |
| Transport | HTTP/1.1 və ya HTTP/2 | Yalnız HTTP/2 |
| Model | Resurslar + HTTP metodları (`GET /orders/7`) | Metodlar (`GetOrder`) |
| Streaming | SSE, WebSocket ilə ayrıca | Daxili: 4 növ |
| Brauzer | Birbaşa işləyir | gRPC-Web və ya gateway lazımdır |
| Kəşləmə | HTTP cache, CDN işləyir | Praktik olaraq yoxdur (hamısı `POST`) |
| Alətlər | curl, Postman, brauzer | grpcurl, Postman (gRPC), reflection |

**gRPC:** daxili mikroservislər arası sıx trafik, çoxdilli komanda, streaming, mobil klientdə trafikə qənaət.
**REST:** ictimai API, brauzer klientləri, üçüncü tərəf inteqrasiyaları, HTTP cache lazım olan hallar.

Çox yayılmış arxitektura: xaricdə REST (və ya GraphQL) gateway, daxildə gRPC.
</details>

<details>
<summary><b>3. Protocol Buffers (protobuf) nədir? JSON-dan niyə daha kiçikdir?</b></summary>

Protobuf dildən asılı olmayan, sxemə əsaslanan binary serializasiya formatıdır. JSON-da hər obyekt sahə adlarını mətn kimi daşıyır (`"customerName":"Aysel"`). Protobuf-da isə sahə adı yox, yalnız **sahə nömrəsi** və tip (bir baytda: tag) göndərilir, ədədlər isə **varint** ilə kodlanır: kiçik ədəd 1 bayt tutur. Default dəyərlər (0, boş sətir, `false`) ümumiyyətlə göndərilmir.

Nəticə: mesaj adətən JSON-dan bir neçə dəfə kiçik olur və parse etmək üçün mətn təhlili lazım deyil. Qiyməti: binary oxunaqlı deyil, oxumaq üçün sxem lazımdır.
</details>

<details>
<summary><b>4. Sahə nömrələri nə üçündür? Onları dəyişmək olarmı?</b></summary>

```protobuf
message Order {
  int64 id = 1;
  string customer = 2;
  double amount = 3;
}
```

Nömrə sahənin telin üstündəki (wire) kimliyidir. Ad yalnız generasiya olunan kodda istifadə olunur. Buradan qaydalar çıxır:

- **Nömrəni heç vaxt dəyişməyin** və başqa sahəyə təkrar verməyin: köhnə klient `3`-ü hələ `amount` kimi oxuyacaq.
- Sahənin **adını** dəyişmək tel səviyyəsində təhlükəsizdir (amma JSON transcoding və generasiya olunan kod üçün yox).
- Sahəni silərkən nömrəsini və adını `reserved` edin: `reserved 3; reserved "amount";`. Beləcə kimsə gələcəkdə səhvən onu yenidən istifadə edə bilməz.
- 1-15 nömrələri tag üçün 1 bayt tutur, ona görə tez-tez göndərilən sahələrə verilir.
</details>

<details>
<summary><b>5. Protobuf-da geriyə və irəliyə uyğunluq necə təmin olunur?</b></summary>

- **Yeni sahə əlavə etmək təhlükəsizdir.** Köhnə klient tanımadığı sahəni ötürür (unknown field kimi saxlayır), yeni server köhnə klientdən gəlməyən sahəni default dəyərlə görür.
- **Tipi dəyişmək təhlükəlidir.** Bəzi dəyişikliklər uyğundur (`int32` ↔ `int64`, amma kəsilmə ola bilər), çoxu deyil (`string` → `int64`).
- **Sahəni silmək:** `reserved` ilə (sual 4).
- **Enum-a dəyər əlavə etmək:** köhnə klient tanımadığı dəyəri alır; ona görə enum-un sıfır dəyəri həmişə `..._UNSPECIFIED = 0` olmalıdır.
- **proto3-də `required` yoxdur**, çünki required sahəni sonradan silmək bütün köhnə klientləri sındırır.

Uyğunsuz dəyişiklik lazımdırsa, yeni versiya yaradılır: `package orders.v2;`. Bu yoxlamaları CI-da `buf breaking` avtomatik edir.
</details>

<details>
<summary><b>6. proto3-də "sahə göndərilməyib" ilə "sahə 0-dır" arasında necə fərq qoymaq olar?</b></summary>

proto3-də skalyar sahələrin default dəyəri telə yazılmır, ona görə `amount = 0` ilə "amount göndərilməyib" eyni görünür. Həll yolları:

- `optional double amount = 3;`: generasiya olunan kodda `hasAmount()` metodu yaranır.
- Wrapper tipləri: `google.protobuf.DoubleValue` (mesajdır, ona görə `hasAmount()` var).
- PATCH semantikası üçün `google.protobuf.FieldMask`: "hansı sahələri yeniləyirəm" siyahısı.

Mesaj tipli sahələrdə bu problem yoxdur: onlar üçün həmişə `hasX()` var.
</details>

<details>
<summary><b>7. <code>oneof</code> və well-known type-lar nədir?</b></summary>

`oneof`: bir neçə sahədən **ən çoxu biri** dolu ola bilər. Yaddaşa qənaət edir və "ya o, ya bu" məntiqini sxemdə göstərir:

```protobuf
message Payment {
  oneof method {
    Card card = 1;
    BankTransfer transfer = 2;
  }
}
```

Java-da `getMethodCase()` hansının dolu olduğunu qaytarır.

**Well-known types** Google-un hazır mesajlarıdır: `Timestamp` (vaxt), `Duration`, `Empty` (boş sorğu/cavab), `Any` (istənilən mesaj), `Struct` (sərbəst JSON), wrapper-lər (`StringValue`, `Int64Value`), `FieldMask`. Tarix üçün `string` və ya `int64` əvəzinə `Timestamp` istifadə edin: bütün dillərdə düzgün tipə çevrilir.
</details>

<details>
<summary><b>8. gRPC niyə HTTP/2 üzərində qurulub? HTTP/2 nə verir?</b></summary>

- **Multiplexing:** bir TCP bağlantısında çoxlu paralel stream. HTTP/1.1-dəki "head-of-line blocking" (bir cavab gələnə qədər növbəti gözləyir) və çox bağlantı açmaq ehtiyacı aradan qalxır.
- **Binary framing:** mesajlar frame-lərə bölünür, parse sadədir.
- **Header sıxılması (HPACK):** eyni header-lər hər sorğuda təkrar göndərilmir.
- **Çift tərəfli stream:** bir stream-də hər iki tərəf müstəqil frame göndərə bilir; bu, bidirectional streaming-in əsasıdır.
- **Flow control:** hər stream və bağlantı səviyyəsində pəncərə (window).
- **Trailer-lər:** cavab gövdəsindən sonra header göndərmək. gRPC status kodu (`grpc-status`) məhz trailer-də gəlir, çünki stream-in sonunda məlum olur.
</details>

### Dörd çağırış növü

<details>
<summary><b>9. gRPC-də hansı dörd çağırış növü var? Hər birinə nümunə verin.</b></summary>

```protobuf
rpc GetOrder(GetOrderRequest) returns (Order);                 // unary
rpc ListOrders(ListOrdersRequest) returns (stream Order);      // server streaming
rpc UploadOrders(stream Order) returns (UploadSummary);        // client streaming
rpc Chat(stream ChatRequest) returns (stream ChatResponse);    // bidirectional
```

| Növ | Nümunə |
|---|---|
| **Unary** | Sifarişi id ilə almaq, ödəniş etmək: adi RPC |
| **Server streaming** | Böyük siyahını hissə-hissə göndərmək, qiymət yeniləmələrinə abunə, LLM cavabı token-token |
| **Client streaming** | Telemetriya, log və ya fayl yükləmək; sonda bir yekun cavab |
| **Bidirectional** | Chat, oyun, canlı tərcümə, iki tərəfli sinxronizasiya |

Bu repoda dördü də [`orders.proto`](../grpc-streaming/src/main/proto/orders.proto)-dadır (fəsillər 13-16).
</details>

<details>
<summary><b>10. Server streaming ilə REST-də pagination arasında nə fərq var?</b></summary>

Pagination-da klient hər səhifəni ayrıca sorğu ilə istəyir: hər dəfə yeni round trip, sorğular arasında məlumat dəyişə bilər (cursor/offset problemləri), amma hər sorğu müstəqildir və kəşlənə bilər.

Server streaming-də bir çağırış açılır və server elementləri hazır olduqca göndərir: birinci element dərhal gəlir, bütün nəticəni yaddaşa yığmaq lazım deyil. Qiyməti: çağırış uzun müddət açıq qalır (deadline və load balancer timeout-ları nəzərə alınmalıdır), qırılsa, klient haradan davam edəcəyini özü bilməlidir (məsələn, son id-ni göndərərək).
</details>

<details>
<summary><b>11. Bidirectional streaming-də iki tərəf bir-birini gözləyirmi?</b></summary>

Xeyr. İki axın **müstəqildir**: server klientin bütün mesajlarını gözləmədən cavab göndərə bilər, klient də cavab gözləmədən yeni mesaj göndərə bilər. Mesajların sırası hər istiqamətin **öz daxilində** qorunur, iki istiqamət arasında isə heç bir sıra zəmanəti yoxdur.

Protokolu (məsələn, "hər sorğuya bir cavab" və ya "ping-pong") tətbiq özü müəyyən edir. Bir tərəf axınını bağlayanda (`onCompleted`) digər tərəf hələ göndərməyə davam edə bilər (half-close).
</details>

<details>
<summary><b>12. Java-da <code>StreamObserver</code> necə işləyir?</b></summary>

`StreamObserver<T>` üç metodlu callback interfeysidir: `onNext(T)`, `onError(Throwable)`, `onCompleted()`.

- **Unary və server streaming serverində** metod `StreamObserver<Response>` alır: `onNext` ilə cavab(lar) göndərilir, `onCompleted` ilə bitirilir.
- **Client streaming və bidi serverində** metod klientin mesajlarını qəbul etmək üçün **öz** `StreamObserver<Request>`-ini qaytarır; cavablar isə parametr kimi gələn observer-ə yazılır.

Qaydalar: `onError` və ya `onCompleted` yalnız bir dəfə və sonda çağırılır; `StreamObserver` **thread-safe deyil**, bir neçə thread-dən eyni vaxtda `onNext` çağırmaq olmaz (sinxronlaşdırın və ya bir thread-dən göndərin).
</details>

### Deadline, ləğv, xətalar

<details>
<summary><b>13. Deadline nədir və timeout-dan nə ilə fərqlənir?</b></summary>

**Timeout** nisbi müddətdir: "5 saniyə gözlə". **Deadline** mütləq zaman nöqtəsidir: "saat 12:00:05-ə qədər". gRPC deadline ilə işləyir:

```java
stub.withDeadlineAfter(2, TimeUnit.SECONDS).getOrder(request);
```

Deadline `grpc-timeout` header-i ilə serverə ötürülür. Vaxt bitəndə klient `DEADLINE_EXCEEDED` alır, server tərəfdə isə çağırış ləğv olunur (`Context` ləğv edilir).

gRPC-də **default deadline yoxdur**: deadline qoyulmayan çağırış sonsuza qədər gözləyə bilər. Ona görə hər çağırışa deadline qoymaq ən vacib production qaydalarından biridir.
</details>

<details>
<summary><b>14. Deadline propagation nədir və niyə vacibdir?</b></summary>

A → B → C zəncirində A 2 saniyə deadline qoyub. B C-ni çağıranda yeni 5 saniyəlik timeout qoysa, A artıq imtina etsə də B və C boş yerə 5 saniyə işləyəcək.

Deadline propagation-da B C-ni **qalan vaxtla** çağırır: A-dan 2 s gəldi, B 300 ms sərf etdi, C üçün ~1.7 s qalır. gRPC Java-da server handler-inin `Context`-indəki deadline eyni thread-də edilən çıxış çağırışlarına avtomatik keçir. Nəticədə bütün zəncir eyni anda imtina edir, resurs boşa getmir. `Context` başqa thread-ə keçəndə isə onu əl ilə ötürmək lazımdır (`Context.current().wrap(...)`).
</details>

<details>
<summary><b>15. Klient çağırışı ləğv edəndə serverdə nə baş verir?</b></summary>

Klient `cancel` edəndə, deadline bitəndə və ya bağlantı qırılanda HTTP/2 `RST_STREAM` frame-i gedir və server tərəfdə çağırışın `Context`-i ləğv olunur. Server bunu yoxlamalıdır, əks halda boş yerə işləməyə davam edər:

```java
ServerCallStreamObserver<Order> call = (ServerCallStreamObserver<Order>) responseObserver;
call.setOnCancelHandler(() -> log.info("client left"));
...
if (call.isCancelled()) return;
```

Ləğv olunmuş çağırışa `onNext` yazmaq xəta atır (`CANCELLED`). Uzun server streaming-də ləğvi yoxlamamaq, müştəri çoxdan getmiş halda bazadan milyonlarla sətir oxumaq deməkdir (fəsil 14).
</details>

<details>
<summary><b>16. gRPC status kodlarını sadalayın. HTTP status kodları ilə necə uyğunlaşır?</b></summary>

gRPC cavabı HTTP səviyyəsində demək olar ki, həmişə `200 OK`-dur; nəticə `grpc-status` trailer-indədir. Ən çox istifadə olunanlar:

| Kod | Nə vaxt | HTTP analoqu |
|---|---|---|
| `OK` | uğur | 200 |
| `INVALID_ARGUMENT` | pis sorğu | 400 |
| `NOT_FOUND` | tapılmadı | 404 |
| `ALREADY_EXISTS` | təkrar yaratmaq | 409 |
| `PERMISSION_DENIED` | icazə yoxdur | 403 |
| `UNAUTHENTICATED` | kimlik yoxlanmayıb | 401 |
| `RESOURCE_EXHAUSTED` | limit, kvota, böyük mesaj | 429 |
| `FAILED_PRECONDITION` | vəziyyət uyğun deyil (məs., göndərilmiş sifarişi ləğv etmək) | 400/409 |
| `DEADLINE_EXCEEDED` | vaxt bitdi | 504 |
| `UNAVAILABLE` | servis müvəqqəti əlçatmazdır, **təkrar cəhd etmək olar** | 503 |
| `INTERNAL` | server xətası | 500 |
| `UNIMPLEMENTED` | metod yoxdur | 501 |
| `CANCELLED` | klient ləğv etdi | 499 |

Retry qərarı koda görə verilir: `UNAVAILABLE` təkrarlanır, `INVALID_ARGUMENT` yox.
</details>

<details>
<summary><b>17. Serverdə xətanı klientə necə qaytarmaq lazımdır?</b></summary>

Exception atmaq yox, `onError`-a `Status` ilə `StatusRuntimeException` vermək:

```java
responseObserver.onError(Status.NOT_FOUND
        .withDescription("order 7 not found")
        .asRuntimeException());
```

İstisnanı tutulmamış buraxsanız, klient heç bir məlumatsız `UNKNOWN` alır. Strukturlaşdırılmış detallar lazımdırsa (hansı sahə səhvdir, nə vaxt təkrar cəhd etməli), Google-un **richer error model**-i istifadə olunur: `com.google.rpc.Status` içində `BadRequest`, `RetryInfo`, `ErrorInfo` mesajları, `StatusProto.toStatusRuntimeException(...)` ilə göndərilir (trailer-də `grpc-status-details-bin`).

Spring gRPC-də bunu mərkəzləşdirmək olar: `@GrpcAdvice` sinfində `@GrpcExceptionHandler` metodları istisnanı `Status`-a çevirir, `@ControllerAdvice` + `@ExceptionHandler` kimi.
</details>

<details>
<summary><b>18. Metadata nədir? Nə üçün istifadə olunur?</b></summary>

Metadata gRPC-nin HTTP header-ləridir: açar-dəyər cütləri, çağırışın əvvəlində (header) və sonunda (trailer) göndərilir. Nümunələr: `authorization: Bearer ...`, `x-request-id`, `traceparent`, dil, klient versiyası.

- Açarlar kiçik hərflə yazılır; `-bin` ilə bitən açarlar binary dəyər daşıyır.
- Java-da `Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER)`.
- Adətən interceptor-da oxunur və yazılır, biznes mesajının içinə qoyulmur.
</details>

### Production

<details>
<summary><b>19. Interceptor nədir? Nümunə verin.</b></summary>

Interceptor hər çağırışın ətrafında işləyən kod parçasıdır: servlet filter-in və ya Spring `HandlerInterceptor`-un gRPC analoqu. Server tərəfdə `ServerInterceptor`, klient tərəfdə `ClientInterceptor`.

Tipik istifadə: autentifikasiya (metadata-dan token oxumaq), log, metrika və trace (Micrometer-in hazır interceptor-ları), request-id ötürmək, rate limit, xətaların çevrilməsi.

Spring Boot 4.1-də `ServerInterceptor` tipli bean-i `@GlobalServerInterceptor` ilə işarələsəniz, bütün servislərə tətbiq olunur. Observability üçün isə Boot özü interceptor qoşur.
</details>

<details>
<summary><b>20. gRPC-də flow control və backpressure necə işləyir?</b></summary>

İki səviyyədə:

1. **HTTP/2 flow control:** hər stream-in qəbul pəncərəsi var; qəbul edən oxumayanda pəncərə dolur və göndərən tərəf frame göndərə bilmir. Bu avtomatikdir.
2. **Tətbiq səviyyəsi:** `StreamObserver.onNext` bloklamır, mesajları buferə yazır. Göndərən yavaş klientə sürətlə yazsa, bufer yaddaşda böyüyür və `OutOfMemoryError`-a qədər gedə bilər.

Həlli: `ServerCallStreamObserver.isReady()` yoxlamaq və `setOnReadyHandler` ilə "bufer boşaldı, davam et" siqnalını gözləmək. Qəbul tərəfində isə `disableAutoRequest()` + `request(n)` ilə neçə mesaj istədiyini idarə etmək olar, Reactive Streams-dəki kimi. Fəsil 14-də bu addım-addım göstərilir.
</details>

<details>
<summary><b>21. gRPC-də load balancing niyə çətindir? Hansı yolları var?</b></summary>

gRPC uzunömürlü HTTP/2 bağlantısı açır və bütün sorğuları onun üstündən multiplex edir. **L4 (TCP) load balancer** yalnız bağlantı səviyyəsində bölür: klient bir pod-a bağlanır və bütün trafik o pod-a gedir, yeni pod-lar yük almır. Kubernetes-in adi `Service`-i də L4-dür.

Yollar:

- **L7 proxy:** Envoy, Linkerd, Istio, NGINX, hər **sorğunu** ayrıca bölür.
- **Client-side load balancing:** klient DNS və ya service discovery ilə bütün ünvanları alır və `round_robin` siyasəti ilə bölür (Kubernetes-də headless service + `dns:///` target).
- **Lookaside/xDS:** mərkəzi kontrol paneli klientlərə siyasəti ötürür (proxyless service mesh).

Bundan əlavə, server `max-connection-age` qoyaraq klientləri vaxtaşırı yenidən qoşulmağa məcbur edə bilər ki, yük yeni pod-lara da yayılsın.
</details>

<details>
<summary><b>22. gRPC-də retry necə qurulur? Hansı risklər var?</b></summary>

gRPC Java service config ilə daxili retry dəstəkləyir: hansı metodlar, neçə cəhd, hansı status kodlarında (adətən yalnız `UNAVAILABLE`), eksponensial backoff. Retry buferi dolmayıbsa, streaming çağırışlar da təkrarlana bilər (yalnız serverdən cavab gəlməmişdirsə). Bunun üçün kanalda `enableRetry()` aktiv olmalıdır.

Risklər:

- **İdempotentlik:** ödəniş kimi idempotent olmayan çağırışı təkrarlamaq ikiqat ödəniş deməkdir; idempotency açarı istifadə edin.
- **Retry storm:** hər qatda 3 retry varsa, 3 qatlı zəncirdə 27 çağırış olur. Retry-ı bir qatda saxlayın və throttling (`retryThrottling`) qoşun.
- **Deadline:** retry-lar ümumi deadline-ın içində olmalıdır.
</details>

<details>
<summary><b>23. Keepalive nə üçündür?</b></summary>

Uzun müddət boş qalan bağlantını NAT, firewall və ya load balancer xəbərsiz bağlaya bilər; sonra ilk sorğu asılı qalır. Keepalive vaxtaşırı HTTP/2 `PING` frame-i göndərir: bağlantını canlı saxlayır və ölü bağlantını tez aşkar edir.

Diqqət: server tərəfdə `permitKeepAliveTime` klientin ping intervalından böyükdürsə, server klienti "çox ping edir" deyə bağlayır (`GOAWAY` + `too_many_pings`). İki tərəfin ayarları uyğun olmalıdır.
</details>

<details>
<summary><b>24. Mesaj ölçüsünün limiti nədir? Böyük fayl necə göndərilir?</b></summary>

gRPC Java-da qəbul edilən mesajın default maksimal ölçüsü **4 MB**-dır; daha böyük mesaj `RESOURCE_EXHAUSTED` ilə rədd edilir. Limiti artırmaq olar (`maxInboundMessageSize`), amma bütün mesaj yaddaşda saxlanılır, ona görə bu yaxşı həll deyil.

Düzgün yol: faylı **chunk-lara** (məsələn, 64 KB) bölüb **client streaming** (yükləmə) və ya **server streaming** (endirmə) ilə göndərmək. Çox böyük fayllar üçün isə gRPC-dən faylın özünü yox, obyekt saxlama yerinə (S3, MinIO) pre-signed URL ötürmək daha yaxşıdır.
</details>

<details>
<summary><b>25. gRPC-də təhlükəsizlik: TLS, mTLS və autentifikasiya</b></summary>

- **TLS:** production-da kanal həmişə şifrələnməlidir. `usePlaintext()` yalnız lokal inkişaf və ya mesh daxilində (TLS-i sidecar edirsə) istifadə olunur.
- **mTLS:** hər iki tərəf sertifikat təqdim edir; servislər arası kimlik yoxlaması üçün. Service mesh (Istio, Linkerd) bunu avtomatik edir.
- **İstifadəçi autentifikasiyası:** metadata-da `authorization: Bearer <JWT>`, serverdə interceptor ilə yoxlanır (klient tərəfdə `CallCredentials`). Spring Security gRPC server-ə də inteqrasiya olunur.
- **Avtorizasiya** metod səviyyəsində: hansı rol hansı `rpc`-ni çağıra bilər.
</details>

<details>
<summary><b>26. gRPC-ni brauzerdən necə çağırmaq olar?</b></summary>

Brauzer JavaScript-i HTTP/2 frame-lərini və trailer-ləri birbaşa idarə edə bilmir, ona görə adi gRPC brauzerdə işləmir. Yollar:

- **gRPC-Web:** xüsusi protokol və JS klienti; aradakı proxy (Envoy) onu gRPC-yə çevirir. Unary və server streaming dəstəklənir, client streaming və bidi yox.
- **Connect protokolu:** gRPC ilə uyğun, brauzerdə birbaşa işləyən alternativ.
- **JSON transcoding / gRPC-Gateway:** `google.api.http` annotasiyaları ilə gRPC metodunu REST endpoint-i kimi açmaq.
- **Öz HTTP gateway-iniz:** bu repoda olduğu kimi, Spring MVC controller-i daxildə gRPC stub-ını çağırır və nəticəni JSON/SSE kimi qaytarır (fəsil 17).
</details>

<details>
<summary><b>27. gRPC servisini necə test etmək olar?</b></summary>

- **Unit test:** servis sinfinin metodunu birbaşa çağırıb saxta `StreamObserver` ilə nəticəni yoxlamaq.
- **In-process server:** `InProcessServerBuilder` + `InProcessChannelBuilder`: şəbəkəsiz, port açmadan, amma real gRPC stack-i ilə (serializasiya, interceptor-lar, status kodları). Bu repoda testlər belə yazılıb (`grpc-inprocess` asılılığı).
- **Əl ilə:** `grpcurl` və ya Postman. Server reflection aktivdirsə, `.proto` faylı olmadan: `grpcurl -plaintext localhost:9090 list`.
- **Müqavilə testi:** `buf lint` (stil) və `buf breaking` (uyğunluğu pozan dəyişikliklər) CI-da.
</details>

<details>
<summary><b>28. Server reflection və health checking nədir?</b></summary>

**Reflection** servisi (`grpc.reflection.v1.ServerReflection`) serverdə hansı servis və metodların olduğunu və onların sxemini qaytarır. grpcurl, Postman kimi alətlər `.proto` faylı olmadan işləyə bilir. Daxili mühitdə faydalıdır; xarici API-də bəzən söndürülür ki, sxem ifşa olunmasın.

**Health checking** standart `grpc.health.v1.Health` servisidir: `Check` (bir dəfə) və `Watch` (stream) metodları `SERVING`/`NOT_SERVING` qaytarır. Kubernetes-in native gRPC probe-u, load balancer-lər və service mesh bunu istifadə edir. Spring Boot 4.1-də həm reflection, həm health servisi avtomatik qoşula bilir.
</details>

<details>
<summary><b>29. Spring Boot-da gRPC servisi necə yazılır?</b></summary>

Spring Boot 4.1-də gRPC üçün rəsmi starter-lər var (Spring gRPC layihəsi əsasında):

```groovy
implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
implementation 'org.springframework.boot:spring-boot-starter-grpc-client'
```

- Server: generasiya olunmuş `OrderServiceGrpc.OrderServiceImplBase`-i genişləndirən sinfi bean edin (`@Service`); Boot onu avtomatik qeydiyyatdan keçirir və Netty serveri `spring.grpc.server.port`-da (default 9090) qaldırır.
- Klient: kanallar `spring.grpc.client.channels.<ad>.target` ilə konfiqurasiya olunur, stub-lar `GrpcChannelFactory` ilə yaradılır.
- `.proto`-dan kod generasiyası build-də `com.google.protobuf` Gradle plugin-i ilə edilir.
- Observability (Micrometer trace + metrika), health, reflection, TLS və interceptor-lar konfiqurasiya ilə gəlir.
</details>

<details>
<summary><b>30. gRPC-ni nə vaxt istifadə etməmək lazımdır?</b></summary>

- **İctimai API**, klientləri naməlum üçüncü tərəflərdir: REST/JSON hamıya tanışdır, curl ilə sınanır, sənədləşdirmə (OpenAPI) standartdır.
- **Brauzer əsas klientdirsə** və əlavə proxy qatı istəmirsinizsə.
- **HTTP cache və CDN** vacibdirsə: gRPC çağırışları `POST`-dur və kəşlənmir.
- **Komanda və infrastruktur hazır deyilsə:** L7 load balancer, proto idarəetməsi, alətlər lazımdır; binary trafikə tcpdump ilə baxmaq çətindir.
- **Sadə CRUD** və az trafik: gRPC-nin üstünlükləri (performans, streaming) hiss olunmayacaq, mürəkkəblik isə qalacaq.

Qısa cavab: gRPC daxili, sıx, çoxdilli servislər arası əlaqə üçün əladır; xarici dünya ilə sərhəddə isə adətən REST qalır. Seçim haqqında ətraflı: [Sonsöz](18-secim.md).
</details>

---

[← II hissə, WebFlux sualları](musahibe-2-webflux.md) · [Mündəricat](README.md)
