# Streaming bələdçisi

Serverdən cavabı hissə-hissə göndərmək haqqında qısa kitab: Spring MVC, WebFlux və gRPC, Java 21 və Spring Boot 4 ilə. IV hissə isə eyni üslubda digər modulların mövzularını izah edir. Hər fəsil sadə analogiya ilə başlayır, problemi göstərir, həlli bu repodakı işlək kodla addım-addım izah edir və tapşırıqla bitir.

Başlamazdan əvvəl bütün nümunələri işə salın:

```bash
docker compose up --build
```

## Mündəricat

**[Giriş: Cavab niyə hissə-hissə gəlməlidir?](00-giris.md)**

### I hissə: Spring MVC ([`mvc-streaming`](../mvc-streaming), http://localhost:8080)
1. [Servlet modeli: bir request, bir thread](01-servlet-modeli.md)
2. [Server-Sent Events: chat cavabı söz-söz](02-sse.md)
3. [SSE ilə uzun işin gedişatı və yenidən qoşulma](03-yeniden-qosulma.md)
4. [NDJSON: böyük siyahını sətir-sətir göndərmək](04-ndjson.md)
5. [Fayl yükləmə: `StreamingResponseBody`](05-fayl-streaming.md)
6. [Production-da streaming: görünməyən tələlər](06-production.md)
   - 🎯 [30 müsahibə sualı: Spring MVC, servlet, SSE](musahibe-1-spring-mvc.md)

### II hissə: WebFlux ([`webflux-streaming`](../webflux-streaming), http://localhost:8081)
7. [Event loop: WebFlux adi request-dən nə ilə fərqlənir](07-event-loop.md)
8. [`Mono` və `Flux`: gələcəyin təsviri](08-mono-flux.md)
9. [Paralel çağırışlar: `Mono.zip`](09-paralel.md)
10. [Reaktiv streaming: `Flux` qaytarmaq kifayətdir](10-reaktiv-streaming.md)
11. [Backpressure və ləğv](11-backpressure.md)
   - 🎯 [30 müsahibə sualı: reaktiv proqramlaşdırma və WebFlux](musahibe-2-webflux.md)

### III hissə: gRPC ([`grpc-streaming`](../grpc-streaming), http://localhost:8082, gRPC `localhost:9090`)
12. [gRPC nədir: müqavilə, protobuf, HTTP/2](12-grpc-nedir.md)
13. [Unary: bir sorğu, bir cavab](13-unary.md)
14. [Server streaming və flow control](14-server-streaming.md)
15. [Client streaming: çox sorğu, bir cavab](15-client-streaming.md)
16. [Bidirectional streaming: hər iki tərəf danışır](16-bidirectional.md)
17. [Brauzer, gateway və gRPC alətləri](17-brauzer-ve-gateway.md)
   - 🎯 [30 müsahibə sualı: gRPC, protobuf, HTTP/2](musahibe-3-grpc.md)

**[Sonsöz: Hansını nə vaxt seçməli?](18-secim.md)**

### IV hissə: Əlavə mövzular
19. [Spring Batch: böyük faylı etibarlı emal etmək](19-spring-batch.md) ([`spring-batch`](../spring-batch), http://localhost:8085)
20. [Kafka: event-driven arxitektura və etibarlı mesajlaşma](20-kafka.md) ([`kafka-events`](../kafka-events), http://localhost:8084) + 30 müsahibə sualı
21. [Redis: keşləmə və yaddaşdakı məlumat strukturları](21-redis.md) ([`redis-cache`](../redis-cache), http://localhost:8088) + 30 müsahibə sualı
22. [Resilience4j: xarici servis "xəstələnəndə"](22-resilience.md) ([`resilience`](../resilience), http://localhost:8089) + 30 müsahibə sualı
23. [Spring Security və JWT: API-ni stateless qorumaq](23-security-jwt.md) ([`security-jwt`](../security-jwt), http://localhost:8090) + 30 müsahibə sualı
24. [Observability: trace, metrika və log](24-observability.md) ([`observability`](../observability), http://localhost:8087) + 30 müsahibə sualı
25. [Spring AI: generativ AI-ı backend-ə qoşmaq](25-spring-ai.md) ([`spring-ai`](../spring-ai), http://localhost:8083) + 30 müsahibə sualı
26. [WebSocket və STOMP: real-time chat](26-websocket.md) ([`websocket-chat`](../websocket-chat), http://localhost:8086) + 30 müsahibə sualı

---

Qısa arayış üçün modulların README-lərinə baxın: [mvc-streaming](../mvc-streaming/README.md), [webflux-streaming](../webflux-streaming/README.md), [grpc-streaming](../grpc-streaming/README.md), [spring-batch](../spring-batch/README.md), [kafka-events](../kafka-events/README.md), [redis-cache](../redis-cache/README.md), [resilience](../resilience/README.md), [security-jwt](../security-jwt/README.md), [observability](../observability/README.md), [spring-ai](../spring-ai/README.md).
