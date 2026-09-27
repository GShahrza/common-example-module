# 10. Reaktiv streaming: `Flux` qaytarmaq kifayətdir

[← 9. Paralel çağırışlar: `Mono.zip`](09-paralel.md) · [Mündəricat](README.md) · Növbəti: [11. Backpressure və ləğv →](11-backpressure.md)

**Hissə II: WebFlux** · **Kod:** [`ChatController`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/chat/ChatController.java), [`OrderController`](../webflux-streaming/src/main/java/io/github/gshahrza/streaming/webflux/order/OrderController.java) · **Demo:** 3-cü və 4-cü bölmələr

---

## Həyatdan analogiya

Hissə I-də streaming üçün bir "boru" qurub (emitter) ona əllə su tökürdük (thread, `send()`, bayraq). WebFlux-da isə boru kranın özüdür: `Flux` qaytarırsınız və su öz-özünə axır.

## Eyni chat, iki üslubda

**Spring MVC** (2-ci fəsil):

```java
SseEmitter emitter = new SseEmitter(...);
AtomicBoolean open = new AtomicBoolean(true);
emitter.onCompletion(() -> open.set(false));
emitter.onError(e -> open.set(false));
executor.execute(() -> {
    try {
        for (String token : tokens) {
            if (!open.get()) return;
            emitter.send(SseEmitter.event().name("token").data(...));
            Thread.sleep(delay);
        }
        emitter.complete();
    } catch (IOException e) { ... }
});
return emitter;
```

**WebFlux:**

```java
@GetMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Map<String, Object>>> stream(@RequestParam String prompt) {
    AtomicInteger index = new AtomicInteger();
    Flux<ServerSentEvent<Map<String, Object>>> tokens = Flux.fromIterable(tokens(prompt))
            .delayElements(properties.chatTokenDelay())
            .map(token -> ServerSentEvent.<Map<String, Object>>builder()
                    .id(String.valueOf(index.getAndIncrement()))
                    .event("token")
                    .data(Map.of("text", token))
                    .build());
    return tokens.concatWith(done);
}
```

| MVC-də əllə edilən | WebFlux-da |
|---|---|
| Executor, thread | Yoxdur: `delayElements` taymerdir |
| `send()` | Hər `Flux` elementi avtomatik yazılır |
| `complete()` | `Flux` bitəndə cavab bağlanır |
| "Client getdimi?" bayrağı | Client gedəndə `Flux` avtomatik ləğv olunur |
| `IOException` tutmaq | Lazım deyil |

Brauzer tərəfi eynidir: `EventSource`. SSE formatı da eynidir.

## Bir metod, iki format

```java
@GetMapping(path = "/api/orders",
            produces = {MediaType.APPLICATION_NDJSON_VALUE, MediaType.APPLICATION_JSON_VALUE})
public Flux<Order> orders(@RequestParam(defaultValue = "1000") int count) {
    return repository.findAll(count);
}
```

Formatı client-in `Accept` header-i seçir (content negotiation):

```js
fetch('/api/orders?count=5000', { headers: { Accept: 'application/x-ndjson' } })   // sətir-sətir
fetch('/api/orders?count=5000', { headers: { Accept: 'application/json' } })       // [ ... ]
```

## Maraqlı nüans: JSON massivi də "axır"

Bunu ölçəndə gözlənilməz bir şey gördüm:

| `Accept` | İlk baytlar | Tam cavab |
|---|---|---|
| `application/x-ndjson` | 187 ms | 3104 ms |
| `application/json` | **164 ms** | 3043 ms |

WebFlux `Flux`-u JSON massivi kimi də **yaddaşa yığmadan**, element-element yazır: əvvəl `[`, sonra hər element, sonda `]`. Yəni baytlar tədricən gəlir. Bəs fərq nədir?

```js
const orders = await res.json();   // ← "]" gəlməyincə bitmir
```

Brauzer yarımçıq massivi parse edə bilmir, ona görə istifadəçi yenə sona qədər gözləyir. Demo-da bu belə görünür: "cavab başladı: 164 ms", amma "ilk sətir göstərildi: 3050 ms".

**Nəticə:** cavabın tədricən **gəlməsi** kifayət deyil, onu tədricən **istifadə etmək** mümkün olmalıdır. Bunun üçün hər parçanın müstəqil parse oluna bilən formatı lazımdır: NDJSON və ya SSE.

## Yadda saxla

- WebFlux-da streaming = `Flux` qaytarmaq. Thread, emitter və "client getdimi" bayrağı lazım deyil.
- `produces` siyahısı ilə eyni metod `Accept`-ə görə NDJSON və ya JSON qaytarır.
- JSON massivi baytlarla axsa da, client onu yalnız sonda istifadə edə bilir.

## Tapşırıq

1. `curl -N -H 'Accept: application/json' "localhost:8081/api/orders?count=3000"` və eyni sorğunu `application/x-ndjson` ilə işə salın. Terminalda fərqi görün.
2. `ChatController`-də `delayElements`-i `Flux.interval` əsasında yenidən yazın. Hansı variant daha oxunaqlıdır?

---

[← 9. Paralel çağırışlar: `Mono.zip`](09-paralel.md) · [Mündəricat](README.md) · Növbəti: [11. Backpressure və ləğv →](11-backpressure.md)
