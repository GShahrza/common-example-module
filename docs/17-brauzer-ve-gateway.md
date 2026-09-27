# 17. Brauzer, gateway və gRPC alətləri

[← 16. Bidirectional streaming: hər iki tərəf danışır](16-bidirectional.md) · [Mündəricat](README.md) · Növbəti: [Sonsöz: Hansını nə vaxt seçməli? →](18-secim.md)

**Hissə III: gRPC** · **Kod:** [`GatewayController`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/gateway/GatewayController.java), [`GrpcClientConfig`](../grpc-streaming/src/main/java/io/github/gshahrza/streaming/grpc/gateway/GrpcClientConfig.java), [testlər](../grpc-streaming/src/test/java/io/github/gshahrza/streaming/grpc)

---

## Həyatdan analogiya

Beynəlxalq konfransda natiq ingiliscə danışır, dinləyicilər isə qulaqlıqda azərbaycanca eşidir. Natiq dilini dəyişmir, arada tərcüməçi var.

gRPC servislər arası "ingiliscə"dir. Brauzer onu danışa bilmir, gateway isə tərcüməçidir.

## Brauzer niyə gRPC danışa bilmir?

gRPC HTTP/2-nin iki imkanına güvənir, brauzerin JavaScript API-ləri isə onları vermir:
- **HTTP/2 frame-lərinə birbaşa nəzarət.** `fetch` bunu gizlədir.
- **Trailer-lər.** gRPC statusu (`grpc-status: 5`) cavabın **sonunda**, trailer header-də gəlir. `fetch` trailer-ləri oxuya bilmir.

## Üç həll

| Həll | Necə işləyir | Nə vaxt |
|---|---|---|
| **Gateway** (bu layihə) | Brauzer HTTP/JSON/SSE ilə gateway-ə, gateway gRPC ilə servisə müraciət edir | Ən çox rast gəlinən; BFF (Backend for Frontend) |
| **gRPC-Web** | Xüsusi protokol; Envoy proxy onu gRPC-yə çevirir; brauzerdə `grpc-web` kitabxanası | Frontend-in də `.proto`-dan tipli client istəməsi |
| **Connect** | gRPC ilə uyğun, brauzerdə birbaşa işləyən protokol | Yeni layihələr |

## Bu layihədə gateway

```
Brauzer ──HTTP──► GatewayController (8082) ──gRPC──► OrderGrpcService (9090)
                   gRPC client                        gRPC server
```

Hər ikisi eyni tətbiqdədir, amma gateway servisi **şəbəkə üzərindən**, `localhost:9090` vasitəsilə çağırır. Bu, iki ayrı mikroservis kimi davranır.

Client konfiqurasiyası:

```java
@Bean
ManagedChannel ordersChannel(GrpcChannelFactory channels) {
    return channels.createChannel("orders");     // application.yml: spring.grpc.client.channel.orders
}

@Bean
OrderServiceGrpc.OrderServiceBlockingStub orderBlockingStub(ManagedChannel ordersChannel) {
    return OrderServiceGrpc.newBlockingStub(ordersChannel);
}
```

**`ManagedChannel`** bir və ya bir neçə HTTP/2 əlaqəsini idarə edir. O, bahalı obyektdir: **bir dəfə yaradılır və paylaşılır**, hər sorğuda yaradılmır. Stub-lar isə ucuzdur.

Gateway hər çağırış növünü brauzerə uyğun formata çevirir:

| gRPC | Brauzerə |
|---|---|
| Unary | JSON (status kodu HTTP-yə çevrilir) |
| Server streaming | NDJSON |
| Client streaming | Gateway özü göndərir, brauzerə JSON xülasə gedir |
| Bidirectional | SSE |

## grpcurl: gRPC üçün curl

Reflection aktiv olduğu üçün `grpcurl` `.proto` faylı olmadan işləyir. Bütün əmrlər bu layihədə sınanıb:

```bash
grpcurl -plaintext localhost:9090 list
# grpc.health.v1.Health
# grpc.reflection.v1.ServerReflection
# streaming.orders.v1.OrderService

grpcurl -plaintext -d '{"id": 7}' localhost:9090 streaming.orders.v1.OrderService/GetOrder
grpcurl -plaintext -d '{"count": 3}' localhost:9090 streaming.orders.v1.OrderService/ListOrders
grpcurl -plaintext localhost:9090 grpc.health.v1.Health/Check
# { "status": "SERVING" }
```

`-plaintext` TLS-siz deməkdir. Production-da gRPC TLS ilə işləməlidir.

Health servisi Kubernetes üçün vacibdir: `livenessProbe` və `readinessProbe` üçün `grpc:` probe tipi var.

## gRPC-ni test etmək: iki səviyyə

**1. Servisin özü: in-process transport.** Şəbəkə, port və Spring olmadan, millisaniyələr ərzində:

```java
String name = InProcessServerBuilder.generateName();
server = InProcessServerBuilder.forName(name)
        .addService(new OrderGrpcService(new OrderData(props), props))
        .build().start();
channel = InProcessChannelBuilder.forName(name).build();
blocking = OrderServiceGrpc.newBlockingStub(channel);
```

Burada dörd çağırış növü, status kodları və deadline yoxlanılır ([`OrderGrpcServiceTest`](../grpc-streaming/src/test/java/io/github/gshahrza/streaming/grpc/OrderGrpcServiceTest.java)).

**2. Bütün tətbiq.** `@SpringBootTest` real portlarla gateway → gRPC client → gRPC server zəncirini yoxlayır ([`GatewayIntegrationTest`](../grpc-streaming/src/test/java/io/github/gshahrza/streaming/grpc/GatewayIntegrationTest.java)).

## Yadda saxla

- Brauzer native gRPC danışa bilmir. Həll gateway, gRPC-Web və ya Connect-dir.
- `ManagedChannel`-i bir dəfə yarat və paylaş.
- Reflection və health servisləri `grpcurl` və Kubernetes üçün faydalıdır.
- Servisi in-process transport ilə, tətbiqi `@SpringBootTest` ilə test et.

## Tapşırıq

1. `grpcurl -plaintext localhost:9090 describe streaming.orders.v1.ChatResponse` işə salın.
2. Gateway-ə `GET /api/orders/{id}/status` əlavə edin. O, `GetOrder` çağırıb yalnız statusu qaytarsın, deadline 500 ms olsun.


🎯 III hissəni bitirdiniz. Özünüzü yoxlayın: [gRPC üzrə 30 müsahibə sualı](musahibe-3-grpc.md).

---

[← 16. Bidirectional streaming: hər iki tərəf danışır](16-bidirectional.md) · [Mündəricat](README.md) · Növbəti: [Sonsöz: Hansını nə vaxt seçməli? →](18-secim.md)
