# Sonsöz: Hansını nə vaxt seçməli?

[← 17. Brauzer, gateway və gRPC alətləri](17-brauzer-ve-gateway.md) · [Mündəricat](README.md)

---

Üç texnologiyanı gördünüz. Real layihədə sual belə qoyulur: "Mənim problemimə hansı uyğundur?" Bu fəsil bütün bələdçini bir yerə yığır.

## Ölçmə nəticələri: bir cədvəldə

Hamısı bu repoda, 4 nüvəli maşında, Docker-də ölçülüb:

| Nə ölçüldü | Nəticə | Fəsil |
|---|---|---|
| 2000 sətir: adi JSON, ilk sətir | 3036 ms | [4](04-ndjson.md) |
| 2000 sətir: NDJSON, ilk sətir | **155 ms** | [4](04-ndjson.md) |
| SSE chat, ilk söz | 7 ms | [2](02-sse.md) |
| WebFlux, 100 paralel sorğu: bloklayan kod | ~8000 ms | [7](07-event-loop.md) |
| WebFlux, 100 paralel sorğu: reaktiv kod | **~380 ms** | [7](07-event-loop.md) |
| 3 servis: ardıcıl / `Mono.zip` | 1200 / **500** ms | [9](09-paralel.md) |
| 100 000 sətirdən 150-si oxunub dayandırıldı: sorğulanan səhifə | 2 / 1000 | [11](11-backpressure.md) |
| 1000 sifariş: JSON / protobuf | 99.7 / **27.9** KB | [12](12-grpc-nedir.md) |

## Problemdən texnologiyaya

| Mənə lazımdır ki... | Seçim |
|---|---|
| ...brauzer chat cavabını söz-söz göstərsin | SSE ([2](02-sse.md), [10](10-reaktiv-streaming.md)) |
| ...uzun işin faizi canlı yenilənsin, əlaqə kəsilsə davam etsin | SSE + event id ([3](03-yeniden-qosulma.md)) |
| ...böyük siyahı yükləndikcə göstərilsin | NDJSON ([4](04-ndjson.md)) |
| ...milyon sətirlik fayl yaddaşı doldurmadan yüklənsin | `StreamingResponseBody` ([5](05-fayl-streaming.md)) |
| ...mobil tətbiq (Android/iOS) və ya SPA üçün adi API: siyahı, detal, yaratmaq, yeniləmək (JPA/JDBC ilə) | REST + Spring MVC + virtual thread-lər ([1](01-servlet-modeli.md); nümunələr aşağıda: [Real klientlər](#real-klientlər-kim-çağırır)) |
| ...bir sorğu bir neçə servisi paralel çağırsın | WebFlux + `Mono.zip` ([9](09-paralel.md)); MVC-də virtual thread + `CompletableFuture` |
| ...yavaş client serveri boğmasın | WebFlux backpressure ([11](11-backpressure.md)), gRPC flow control ([14](14-server-streaming.md)) |
| ...servislər arası sürətli, tipli, çox dilli əlaqə | gRPC ([12](12-grpc-nedir.md)) |
| ...servis böyük məlumatı axınla göndərsin/qəbul etsin | gRPC server/client streaming ([14](14-server-streaming.md), [15](15-client-streaming.md)) |
| ...iki servis arasında davamlı iki tərəfli əlaqə | gRPC bidirectional ([16](16-bidirectional.md)) |
| ...brauzer və ya mobil tətbiqlə iki tərəfli əlaqə (chat) | WebSocket + STOMP ([websocket-chat](../websocket-chat/README.md)) |

## Real klientlər: kim çağırır?

Texnologiyanı seçərkən ən vacib sual API-ni **kimin** çağıracağıdır. Server tərəfdə adətən eyni Spring Boot tətbiqi olur, dəyişən isə protokol və API-nin forması olur.

| Klient | Tipik tələb | Seçim | Bu repoda |
|---|---|---|---|
| **Mobil tətbiq (Android/iOS):** siyahı, detal, sifariş yaratmaq | Səhifələnmiş JSON, versiyalanmış URL, təkrar cəhdə davamlı `POST` | REST + Spring MVC + virtual thread-lər (JPA/JDBC) | nümunə aşağıda |
| **Mobil tətbiq:** tətbiq açıq ikən canlı status (kuryer yolda, ödəniş təsdiqi) | Serverdən axın | SSE | `mvc-streaming`: `/api/jobs/{id}/events` |
| **Mobil tətbiq:** tətbiq bağlı və ya arxa fondadır | Bildiriş | **Push** (FCM/APNs); SSE və WebSocket işləmir, çünki OS bağlantını bağlayır | — |
| **Mobil tətbiq:** chat, canlı iki tərəfli əlaqə | İki istiqamətli | WebSocket + STOMP | `websocket-chat` |
| **Mobil tətbiq:** çox məlumat, zəif şəbəkə, öz komandanızın tətbiqi | Kiçik payload, tipli müqavilə, streaming | gRPC: mobil platformalarda HTTP/2 var, gateway lazım deyil | `grpc-streaming`: `OrderService` |
| **Brauzer (SPA: React, Angular)** | JSON, canlı yeniləmə | REST + SSE; gRPC isə yalnız gateway və ya gRPC-Web ilə | `mvc-streaming`, `webflux-streaming` |
| **Öz backend servisləriniz** | Sürət, tipli müqavilə | gRPC (sinxron) və ya Kafka (asinxron event) | `grpc-streaming`, `kafka-events` |
| **Üçüncü tərəf (partnyor, açıq API)** | Hamının bildiyi format, sənədləşmə | REST + OpenAPI, webhook-lar | — |
| **Çoxlu servisi birləşdirən API gateway / BFF** | Paralel çağırışlar, öz DB-si yoxdur | WebFlux və ya MVC + virtual thread | `webflux-streaming` (`Mono.zip`) |

### Nümunə: mobil tətbiq üçün REST API

Aşağıdakı kod bu repoda yoxdur. Bu, mobil tətbiqin çağırdığı tipik "adi CRUD" API-nin necə göründüyünü göstərir: Spring MVC, JPA və virtual thread-lər.

```java
// application.yml: spring.threads.virtual.enabled: true
// Hər request ayrıca virtual thread-də işləyir: JPA-nın bloklayan çağırışları ucuzdur,
// minlərlə eyni vaxtlı mobil istifadəçi üçün thread pool bitmir.

@RestController
@RequestMapping("/api/v1/orders")   // v1: köhnə tətbiq versiyaları illərlə işləyir, URL-i sındırmaq olmaz
class MobileOrderController {

    record OrderDto(long id, String status, BigDecimal total, Instant createdAt) { }
    record PageDto<T>(List<T> items, int page, int size, boolean hasNext) { }
    record NewOrder(@NotEmpty List<@Valid Item> items, @NotBlank String address) { }

    private final OrderRepository orders;          // Spring Data JPA
    private final IdempotencyService idempotency;

    // GET /api/v1/orders?page=0&size=20: "Sifarişlərim" ekranı, sonsuz scroll
    @GetMapping
    PageDto<OrderDto> myOrders(@AuthenticationPrincipal Jwt user,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        Slice<Order> slice = orders.findByCustomerId(user.getSubject(),
                PageRequest.of(page, Math.min(size, 50), Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageDto<>(slice.map(OrderDto::from).getContent(), page, size, slice.hasNext());
    }

    // POST /api/v1/orders: mobil şəbəkə kəsilə bilər və tətbiq sorğunu təkrarlayır.
    // Idempotency-Key (tətbiqin yaratdığı UUID) eyni sifarişin iki dəfə yaranmasının qarşısını alır.
    @PostMapping
    ResponseEntity<OrderDto> create(@AuthenticationPrincipal Jwt user,
                                    @RequestHeader("Idempotency-Key") UUID key,
                                    @Valid @RequestBody NewOrder request) {
        OrderDto created = idempotency.once(key, () -> OrderDto.from(orders.save(Order.of(user.getSubject(), request))));
        return ResponseEntity.created(URI.create("/api/v1/orders/" + created.id())).body(created);
    }
}
```

Mobil API-ni brauzer API-sindən fərqləndirən məqamlar:

- **Versiya.** Brauzer hər dəfə yeni JavaScript yükləyir, mobil tətbiqin köhnə versiyası isə telefonlarda illərlə qalır. Ona görə `/api/v1` saxlanılır, yeni sahələr əlavə olunur, köhnələr silinmir.
- **Səhifələmə.** Mobil ekran bir anda 20 sətir göstərir. `Slice` (`hasNext`) `Page`-dən ucuzdur, çünki `COUNT(*)` sorğusu etmir.
- **Idempotency.** Metroda şəbəkə kəsildi, tətbiq `POST`-u təkrarladı; açar olmasa, iki sifariş yaranar.
- **Kiçik cavab.** Mobil trafik pullu və yavaşdır. Yalnız lazımi sahələri qaytarın və gzip-i yandırın (`server.compression.enabled: true`).
- **Autentifikasiya.** OAuth2/OIDC ilə JWT (Spring Security `oauth2-resource-server`); refresh token tətbiqdə təhlükəsiz saxlanılır (Keychain, Keystore). İşlək nümunə: [security-jwt](../security-jwt/README.md).

### Android (Kotlin)

REST, Retrofit ilə:

```kotlin
interface OrdersApi {
    @GET("api/v1/orders")
    suspend fun myOrders(@Query("page") page: Int, @Query("size") size: Int = 20): PageDto<OrderDto>

    @POST("api/v1/orders")
    suspend fun create(@Header("Idempotency-Key") key: String, @Body order: NewOrder): OrderDto
}

val api = Retrofit.Builder()
    .baseUrl("https://api.example.az/")
    .client(okHttp)                                   // Authorization header-i interceptor əlavə edir
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(OrdersApi::class.java)

// ViewModel-də
viewModelScope.launch {
    val key = UUID.randomUUID().toString()           // bir dəfə yaradılır, təkrar cəhdlərdə eyni qalır
    val order = retry(times = 3) { api.create(key, newOrder) }   // retry: öz köməkçi funksiyanız (backoff ilə)
}
```

Canlı status üçün SSE, bu repodakı `mvc-streaming` job-una qoşulma (`okhttp-sse` ilə):

```kotlin
// Emulyatordan kompüterdəki localhost: 10.0.2.2
val request = Request.Builder().url("http://10.0.2.2:8080/api/jobs/$jobId/events").build()
val sseClient = okHttp.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()   // axında read timeout olmamalıdır

EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
    override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
        when (type) {
            "progress" -> lastEventId = id                         // {"step":3,"percent":30,"message":"..."}
            "completed" -> source.cancel()
        }
    }
    override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
        // şəbəkə qayıdanda ?lastEventId=$lastEventId ilə yenidən qoşulun (3-cü fəsil)
    }
})
```

gRPC, bu repodakı `grpc-streaming` servisi (`grpc-kotlin` + `grpc-okhttp`):

```kotlin
val channel = ManagedChannelBuilder.forAddress("10.0.2.2", 9090).usePlaintext().build()   // production-da TLS
val stub = OrderServiceGrpcKt.OrderServiceCoroutineStub(channel)

val order = stub.getOrder(getOrderRequest { id = 42 })             // unary
stub.listOrders(listOrdersRequest { count = 500 })                 // server streaming → Flow<Order>
    .collect { adapter.add(it) }                                   // sifarişlər gəldikcə siyahıya düşür
```

### iOS (Swift)

REST, `URLSession` və `async/await` ilə:

```swift
struct OrderDto: Decodable { let id: Int64; let status: String; let total: Decimal; let createdAt: Date }
struct PageDto<T: Decodable>: Decodable { let items: [T]; let page: Int; let size: Int; let hasNext: Bool }

func myOrders(page: Int) async throws -> PageDto<OrderDto> {
    var url = URLComponents(string: "https://api.example.az/api/v1/orders")!
    url.queryItems = [URLQueryItem(name: "page", value: "\(page)"), URLQueryItem(name: "size", value: "20")]
    var request = URLRequest(url: url.url!)
    request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

    let (data, response) = try await URLSession.shared.data(for: request)
    guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    return try decoder.decode(PageDto<OrderDto>.self, from: data)
}
```

Canlı status üçün SSE: xüsusi kitabxana lazım deyil, cavab sətir-sətir oxunur.

```swift
// Simulyatordan kompüterdəki server: localhost
let url = URL(string: "http://localhost:8080/api/jobs/\(jobId)/events")!
let (bytes, _) = try await URLSession.shared.bytes(from: url)
var event = ""
for try await line in bytes.lines {                       // boş sətirlər ötürülür
    if line.hasPrefix("event:") { event = String(line.dropFirst(6)) }
    if line.hasPrefix("data:"), event == "progress" {
        let progress = try JSONDecoder().decode(Progress.self, from: Data(line.dropFirst(5).utf8))
        await MainActor.run { self.percent = progress.percent }
    }
    if event == "completed" { break }
}
```

gRPC üçün `grpc-swift` eyni `.proto` faylından Swift client yaradır. Android və iOS eyni müqavilədən istifadə edir, JSON sahə adlarında uyğunsuzluq olmur.

## Spring MVC, yoxsa WebFlux?

Java 21-dən sonra bu sualın cavabı dəyişib.

**Əvvəl:** "Çoxlu eyni vaxtlı əlaqə? Thread-lər bitir, WebFlux-a keç."

**İndi:** virtual thread-lər bloklayan kodu ucuz edir. Spring MVC + virtual thread-lər adi servislərdə WebFlux-un performans üstünlüyünün böyük hissəsini verir, üstəlik sadə kod, adi stack trace, JPA saxlanılır.

**WebFlux-u seçmək üçün səbəblər:**
- axınların birləşdirilməsi, çevrilməsi, filtrlənməsi mərkəzdədir (`Flux` operatorları çox güclüdür);
- backpressure lazımdır;
- bütün stack onsuz da reaktivdir (R2DBC, reaktiv Kafka və ya Mongo);
- API gateway və ya BFF: çoxlu servisi çağırıb birləşdirən, öz DB-si olmayan servis.

**WebFlux-u seçməmək üçün səbəblər:**
- JPA/JDBC-dən imtina etmək mümkün deyil (7-ci fəsil: bloklama WebFlux-u MVC-dən də yavaş edir);
- komanda reaktiv proqramlaşdırmaya yeni başlayır;
- adi CRUD.

## REST, yoxsa gRPC?

| | REST + SSE/NDJSON | gRPC |
|---|---|---|
| Kim çağırır | Brauzer, üçüncü tərəf | Öz servisləriniz, mobil |
| Müqavilə | İstəyə bağlı | Məcburi, tipli |
| Performans | Kifayətdir | Yüksək (binary, HTTP/2) |
| Streaming | Serverdən | 4 növ |
| Debug | Asan (curl, brauzer) | `grpcurl` lazımdır |

Çox yayılmış arxitektura hər ikisini birləşdirir: **xaricdə REST, daxildə gRPC**. Bu layihənin `grpc-streaming` modulu məhz bunu göstərir (17-ci fəsil).

## Bütün texnologiyalarda eyni qaydalar

Bələdçi boyu bu fikirlər dəfələrlə qarşınıza çıxdı:

1. **Client gedəndə istehsalı dayandır.** MVC-də bayraq və `IOException`, WebFlux-da `cancel`, gRPC-də `isCancelled()`.
2. **Yavaş client-i nəzərə al.** WebFlux-da backpressure, gRPC-də flow control.
3. **Mənbə tənbəl olsun.** Lazy `Stream`, `concatMap`, səhifə-səhifə oxumaq.
4. **Timeout və deadline qoy.** Heç bir əlaqə sonsuza qədər açıq qalmasın.
5. **Aradakı qatları yoxla.** Proxy buferləməsi və gzip streaming-i gizlicə öldürə bilər.
6. **Zamanlamanı test et.** Məzmun düzgün olsa belə, stream buferlənmiş ola bilər.

Uğurlar!

---

[← 17. Brauzer, gateway və gRPC alətləri](17-brauzer-ve-gateway.md) · [Mündəricat](README.md)
