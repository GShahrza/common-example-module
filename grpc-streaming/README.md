# gRPC: dörd çağırış növü

[← Əsas səhifə](../README.md)

> Bu mövzunun ətraflı, kitab üslubunda izahı: [Hissə III: gRPC](../docs/README.md#iii-hissə-grpc-grpc-streaming-httplocalhost8082-grpc-localhost9090)

Demo səhifə: **http://localhost:8082** · gRPC server: **localhost:9090**

## gRPC nədir?

gRPC servislər arası əlaqə üçün RPC framework-dür. Başqa servisin metodunu lokal metod kimi çağırırsan:

```java
Order order = orderService.getOrder(GetOrderRequest.newBuilder().setId(7).build());
```

Arxada üç şey dayanır:

1. **`.proto` müqaviləsi.** API bir faylda təsvir olunur. Server və client kodu bu fayldan avtomatik yaranır, ona görə iki tərəf metod adlarında və sahələrdə heç vaxt "razılaşmamazlığa" düşmür.
2. **Protobuf.** Mesajlar mətn (JSON) yox, kompakt binary formatda göndərilir.
3. **HTTP/2.** Bir TCP əlaqəsi üzərində çoxlu paralel çağırış və hər iki istiqamətdə streaming mümkündür.

| | REST (JSON / HTTP 1.1) | gRPC |
|---|---|---|
| Müqavilə | İstəyə bağlı (OpenAPI) | Məcburi (`.proto`), kod ondan yaranır |
| Format | JSON, mətn | Protobuf, binary: bu nümunədə **3.6 dəfə kiçik** |
| Transport | HTTP/1.1 (və ya 2) | Yalnız HTTP/2 |
| Streaming | SSE/NDJSON ilə, yalnız serverdən | 4 növ, hər iki istiqamətdə |
| Xətalar | HTTP status (404, 500…) | gRPC status (`NOT_FOUND`, `DEADLINE_EXCEEDED`…) |
| Timeout | Client-in öz işi | Deadline hər çağırışın parçasıdır və serverə də ötürülür |
| Brauzer | Birbaşa | Birbaşa yox: gRPC-Web + proxy və ya gateway lazımdır |
| Oxunaqlılıq | curl, brauzer | `grpcurl` və ya generasiya olunmuş client lazımdır |
| Tipik istifadə | Public API, frontend | Mikroservislər arası, mobil, realtime |

## Bu modulun quruluşu

Brauzer native gRPC danışa bilmir: HTTP/2 frame-lərini və "trailer" header-lərini idarə edə bilmir. Real layihələrdə ən çox rast gəlinən həll gateway-dir:

```
Brauzer ──HTTP: JSON / NDJSON / SSE──► GatewayController :8082 ──gRPC──► OrderGrpcService :9090
                                        (gRPC client)                    (gRPC server)
```

Hər ikisi eyni Spring Boot tətbiqindədir. Gateway servisi başqa bir mikroservis kimi, şəbəkə üzərindən (`localhost:9090`) çağırır. Alternativlər: [gRPC-Web](https://github.com/grpc/grpc-web) + Envoy proxy və ya [Connect](https://connectrpc.com/) protokolu.

## Müqavilə: [`orders.proto`](src/main/proto/orders.proto)

```proto
service OrderService {
  rpc GetOrder(GetOrderRequest) returns (Order);                 // 1. unary
  rpc ListOrders(ListOrdersRequest) returns (stream Order);      // 2. server streaming
  rpc UploadOrders(stream Order) returns (UploadSummary);        // 3. client streaming
  rpc Chat(stream ChatRequest) returns (stream ChatResponse);    // 4. bidirectional
}

message Order {
  int64 id = 1;
  string customer = 2;
  string amount = 3;         // protobuf-da decimal yoxdur; double qəpikləri itirərdi
  Status status = 4;
  google.protobuf.Timestamp created_at = 5;
}
```

`= 1`, `= 2` rəqəmləri sahənin binary formatdakı nömrəsidir, sahənin adı göndərilmir. Ona görə sahənin adını dəyişmək təhlükəsizdir, amma nömrəsini dəyişmək olmaz.

`./gradlew :grpc-streaming:build` zamanı `com.google.protobuf` Gradle plugin-i `protoc` kompilyatorunu yükləyir və `build/generated/` qovluğunda `Order`, `OrderServiceGrpc` və digər sinifləri yaradır (bax: [build.gradle](build.gradle)).

---

## 1. Unary: bir sorğu, bir cavab

Adi REST çağırışının gRPC qarşılığıdır.

**Server** ([`OrderGrpcService`](src/main/java/io/github/gshahrza/streaming/grpc/server/OrderGrpcService.java)):

```java
@Override
public void getOrder(GetOrderRequest request, StreamObserver<Order> responseObserver) {
    if (request.getId() > OrderData.MAX_ID) {
        responseObserver.onError(Status.NOT_FOUND.withDescription("Order not found").asRuntimeException());
        return;
    }
    responseObserver.onNext(OrderData.order(request.getId()));   // bir cavab
    responseObserver.onCompleted();
}
```

**Client** ([`GatewayController`](src/main/java/io/github/gshahrza/streaming/grpc/gateway/GatewayController.java)):

```java
Order order = blockingStub.withDeadlineAfter(2, TimeUnit.SECONDS).getOrder(request);
```

**Deadline** hər gRPC çağırışının parçasıdır. Müddət bitəndə client `DEADLINE_EXCEEDED` alır, server də çağırışın ləğv olunduğunu bilir. REST-də bu, client-in öz timeout-udur və server bundan xəbər tutmur.

gRPC status kodları gateway-də HTTP-yə belə çevrilir:

| gRPC | HTTP |
|---|---|
| `NOT_FOUND` | 404 |
| `INVALID_ARGUMENT` | 400 |
| `DEADLINE_EXCEEDED` | 504 |
| `UNAVAILABLE` | 503 |
| digərləri | 502 |

## 2. Server streaming: bir sorğu, çox cavab

SSE və NDJSON-un gRPC qarşılığıdır: siyahılar, canlı yeniləmələr, böyük export-lar.

```java
// Server: onNext() istənilən qədər çağırılır, sonda onCompleted()
for (Order order : page) {
    if (!flow.awaitReady()) return;   // client oxumağa hazır deyilsə, gözlə
    observer.onNext(order);
}
observer.onCompleted();

// Client: blocking stub stream-i Iterator kimi qaytarır
Iterator<Order> orders = blockingStub.listOrders(request);
while (orders.hasNext()) { ... }
```

**Flow control** vacibdir. `onNext()` heç vaxt bloklanmır. Client yavaş oxuyursa, mesajlar server yaddaşında yığılır. [`FlowControl`](src/main/java/io/github/gshahrza/streaming/grpc/server/FlowControl.java) hər mesajdan əvvəl `isReady()`-ni yoxlayır və `setOnReadyHandler` siqnalını gözləyir. Beləliklə istehsalçı client-in sürəti ilə, yəni HTTP/2 flow-control pəncərəsinə uyğun işləyir. Bu, WebFlux-dakı backpressure-un gRPC qarşılığıdır.

**İstehsal ayrı thread-də gedir.** gRPC bir çağırışın callback-lərini (məs. `onReady`) ardıcıl, bir-bir çatdırır. Metodun içində bloklansaq, gözlədiyimiz `onReady` siqnalı da bloklanardı.

**Ləğv:** brauzer "Dayandır" basanda gateway `Context.CancellableContext`-i ləğv edir, çağırış serverdə `CANCELLED` olur (`setOnCancelHandler`) və istehsal dayanır. Bu, bütün zəncir boyu yoxlanılıb: brauzer → gateway → gRPC server.

## 3. Client streaming: çox sorğu, bir cavab

Bulk upload, sensor məlumatları, log göndərmə üçündür.

```java
// Server bir observer qaytarır; client-in hər mesajı onun onNext()-inə gəlir
public StreamObserver<Order> uploadOrders(StreamObserver<UploadSummary> response) {
    return new StreamObserver<>() {
        public void onNext(Order order) { received++; total = total.add(...); }
        public void onCompleted() {           // client "bitdi" dedi
            response.onNext(summary);         // yeganə cavab
            response.onCompleted();
        }
        ...
    };
}

// Client: async stub lazımdır
StreamObserver<Order> requests = asyncStub.uploadOrders(responseObserver);
for (...) requests.onNext(order);
requests.onCompleted();
```

Demo-da 10 000 sifariş bir gRPC çağırışı ilə göndərilir. REST-də bu, ya 10 000 ayrı sorğu, ya da yaddaşda yığılan böyük bir JSON massivi olardı.

## 4. Bidirectional streaming: hər iki tərəf istədiyi vaxt göndərir

Chat, oyun, canlı əməkdaşlıq, WebSocket-in servislər arası qarşılığıdır.

```java
public StreamObserver<ChatRequest> chat(StreamObserver<ChatResponse> response) {
    ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    return new StreamObserver<>() {
        public void onNext(ChatRequest q) { writer.execute(() -> answer(q, response)); }
        public void onCompleted()         { writer.execute(response::onCompleted); }
        ...
    };
}
```

Demo-da suallar 300 ms fasilə ilə göndərilir, cavablar isə eyni çağırışda paralel gəlir. Client ikinci sualı birincinin cavabı bitməmiş göndərə bilər.

**`StreamObserver` thread-safe deyil.** Cavabları eyni anda bir neçə thread yazsa, mesajlar qarışar və ya exception atılar. Ona görə hər çağırış üçün bir "yazıcı" thread istifadə olunur. Nəticədə cavablar növbəyə düşür və sıra ilə gedir.

---

## Spring Boot 4.1 inteqrasiyası

Boot 4.1-də gRPC rəsmi dəstəklənir ([Spring gRPC](https://spring.io/projects/spring-grpc)):

```gradle
implementation 'org.springframework.boot:spring-boot-starter-grpc-server'
implementation 'org.springframework.boot:spring-boot-starter-grpc-client'
```

- **Server:** `OrderServiceGrpc.OrderServiceImplBase`-i extend edən `@Service` bean avtomatik tapılır və 9090 portunda Netty üzərində qeydiyyatdan keçir.
- **Reflection və health:** avtomatik qoşulur. Buna görə `grpcurl` `.proto` faylı olmadan işləyir, Kubernetes isə servisin sağlamlığını yoxlaya bilir.
- **Client:** kanallar `application.yml`-də adla təyin olunur, stub-lar isə `GrpcChannelFactory` ilə yaradılır ([`GrpcClientConfig`](src/main/java/io/github/gshahrza/streaming/grpc/gateway/GrpcClientConfig.java)):

```yaml
spring:
  grpc:
    server:
      port: 9090
    client:
      channel:
        orders:
          target: static://localhost:9090
```

## grpcurl ilə yoxlamaq

[grpcurl](https://github.com/fullstorydev/grpcurl) gRPC üçün curl-dür:

```bash
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext localhost:9090 describe streaming.orders.v1.OrderService

# unary
grpcurl -plaintext -d '{"id": 7}' localhost:9090 streaming.orders.v1.OrderService/GetOrder

# server streaming
grpcurl -plaintext -d '{"count": 3}' localhost:9090 streaming.orders.v1.OrderService/ListOrders

# client streaming və bidirectional: hər sətir bir mesajdır
printf '{"id":1,"amount":"10.50"}\n{"id":2,"amount":"4.50"}\n' | \
  grpcurl -plaintext -d @ localhost:9090 streaming.orders.v1.OrderService/UploadOrders
printf '{"question":"salam"}\n{"question":"gRPC nədir?"}\n' | \
  grpcurl -plaintext -d @ localhost:9090 streaming.orders.v1.OrderService/Chat

# health
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
```

## Testlər

- [`OrderGrpcServiceTest`](src/test/java/io/github/gshahrza/streaming/grpc/OrderGrpcServiceTest.java): gRPC servisini Spring və şəbəkə olmadan, in-process transport ilə yoxlayır. Burada 4 çağırış növü, status kodları və deadline test olunur.
- [`GatewayIntegrationTest`](src/test/java/io/github/gshahrza/streaming/grpc/GatewayIntegrationTest.java): bütün tətbiqi yoxlayır, yəni HTTP gateway → gRPC client → real portda gRPC server zəncirini.

```bash
./gradlew :grpc-streaming:test
```

---

## Üç modulun müqayisəsi: hansını nə vaxt?

Seçim, API-ni **kimin çağırdığından** asılıdır:

| Kim çağırır / nə lazımdır | Seçim | Nümunə |
|---|---|---|
| Mobil tətbiq (Android/iOS) və ya SPA: siyahı, detal, sifariş yaratmaq (JPA/JDBC ilə adi CRUD) | REST + Spring MVC + virtual thread-lər | `GET /api/v1/orders?page=0&size=20`, `POST /api/v1/orders` + `Idempotency-Key` |
| Mobil tətbiq və ya brauzer, tətbiq açıq ikən: canlı status, progress, chat cavabı | SSE: [MVC](../mvc-streaming) və ya [WebFlux](../webflux-streaming) | `/api/jobs/{id}/events` |
| Mobil tətbiq bağlı və ya arxa fondadır | Push bildiriş (FCM/APNs) | — |
| Brauzer və ya mobil tətbiq: böyük siyahı və ya fayl | NDJSON / `StreamingResponseBody` | `/api/orders/stream`, `/api/orders/export.csv` |
| Chat, iki tərəfli canlı əlaqə | WebSocket + STOMP ([websocket-chat](../websocket-chat)) | `/ws` |
| Öz mobil tətbiqiniz, zəif şəbəkə, çox məlumat | gRPC (mobil platformalarda gateway lazım deyil) | `OrderService/ListOrders` |
| Öz backend servisləriniz: sürətli, tipli, çox dilli | gRPC | `OrderService/GetOrder` |
| Servislər arası davamlı iki tərəfli axın | gRPC bidirectional streaming | `OrderService/Chat` |
| Çoxlu servisi paralel çağıran API gateway / BFF | WebFlux (`Mono.zip`) və ya MVC + virtual thread | [webflux-streaming](../webflux-streaming) |

Android (Kotlin, Retrofit, OkHttp SSE, grpc-kotlin) və iOS (Swift, URLSession) üçün kod nümunələri: [bələdçinin sonsözü, "Real klientlər"](../docs/18-secim.md#real-klientlər-kim-çağırır).
