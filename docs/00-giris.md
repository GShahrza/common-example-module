# Giriş: Cavab niyə hissə-hissə gəlməlidir?

[Mündəricat](README.md) · Növbəti: [1. Servlet modeli: bir request, bir thread →](01-servlet-modeli.md)

**Kod:** bütün repo · **İşə salmaq:** `docker compose up --build`

---

## Həyatdan analogiya

Restoranda dörd nəfər üçün sifariş verirsiniz: salat, şorba, kabab, desert. İki cür xidmət mümkündür:

1. Ofisiant **hər şey hazır olana qədər** mətbəxdə gözləyir, sonra dörd yeməyi birdən gətirir. Siz 40 dəqiqə boş masaya baxırsınız.
2. Ofisiant hər yeməyi **hazır olan kimi** gətirir. Salat 5 dəqiqəyə masadadır. Ümumi vaxt eynidir, amma gözləmə hissi tamam başqadır.

Adi HTTP request birinci ofisiant kimi işləyir. Bu bələdçi isə ikincisini necə qurmağı öyrədir.

## Adi request-in həyatı

```
Brauzer                         Server
   │ ── GET /api/orders ───────► │
   │                             │  DB-dən 1-ci səhifə   (150 ms)
   │         (gözləyir)          │  DB-dən 2-ci səhifə   (150 ms)
   │                             │  ...
   │                             │  DB-dən 20-ci səhifə  (150 ms)
   │                             │  JSON-a çevir
   │ ◄── 200 OK [bütün JSON] ─── │  ← ilk bayt 3-cü saniyədə
```

Bu modeldə üç problem var:

- **İstifadəçi gözləyir.** Məlumatın 95%-i hazır olsa da, ekranda heç nə görünmür.
- **Yaddaş.** Server bütün cavabı yaddaşda yığır. Bir milyon sətirlik export `OutOfMemoryError` ilə bitə bilər.
- **Timeout.** Load balancer 30 saniyə cavab görməsə əlaqəni kəsə bilər, halbuki server işləyirdi.

## Streaming request

```
Brauzer                         Server
   │ ── GET /api/orders/stream ─►│
   │ ◄── 1-ci səhifə ─────────── │  150 ms  → brauzer dərhal göstərir
   │ ◄── 2-ci səhifə ─────────── │  300 ms
   │ ◄── ...                     │
   │ ◄── 20-ci səhifə, son ───── │  3000 ms
```

HTTP əlaqəsi açıq qalır və server məlumatı hissə-hissə yazır. Texniki olaraq bu, HTTP/1.1-də `Transfer-Encoding: chunked`, HTTP/2-də isə ardıcıl DATA frame-ləri deməkdir. Server əvvəlcədən cavabın ölçüsünü (`Content-Length`) bilməli deyil.

## Rəqəmlərlə

Bu repoda eyni 2000 sətir hər iki üsulla ölçülüb (Docker, [mvc-streaming](../mvc-streaming)):

| Üsul | İlk sətir | Tam cavab |
|---|---|---|
| Adi JSON | 3036 ms | 3036 ms |
| NDJSON stream | **155 ms** | 3036 ms |

Ümumi vaxt dəyişmir, çünki fizikanı aldatmaq olmur. Dəyişən odur ki, istifadəçi **20 dəfə tez** nəsə görür.

## Bu bələdçi nədən ibarətdir

| Hissə | Mövzu | Modul |
|---|---|---|
| **I. Spring MVC** | Klassik servlet dünyasında streaming: SSE, NDJSON, fayl | [`mvc-streaming`](../mvc-streaming), port 8080 |
| **II. WebFlux** | Reaktiv model: adi request-dən fərqi, `Mono`/`Flux`, backpressure | [`webflux-streaming`](../webflux-streaming), port 8081 |
| **III. gRPC** | Servislər arası streaming: 4 çağırış növü | [`grpc-streaming`](../grpc-streaming), port 8082 və 9090 |

Hər fəsil eyni quruluşdadır: analogiya, problem, repodakı real kodla həll, tələlər, "yadda saxla" və tapşırıq. Kodu IDE-də açıb yanaşı oxumaq ən yaxşı üsuldur.

## Başlamazdan əvvəl

```bash
docker compose up --build
```

Sonra üç səhifəni açın: http://localhost:8080, http://localhost:8081, http://localhost:8082. Brauzerin DevTools → **Network** tab-ını açıq saxlayın. Streaming sorğularında cavabın bir anda deyil, tədricən gəldiyini "Timing" bölməsində görəcəksiniz.

Terminaldan baxmaq üçün `curl -N` istifadə edin. `-N` curl-ün öz buferləməsini söndürür:

```bash
curl -N "http://localhost:8080/api/chat/stream?prompt=salam"
```

---

[Mündəricat](README.md) · Növbəti: [1. Servlet modeli: bir request, bir thread →](01-servlet-modeli.md)
